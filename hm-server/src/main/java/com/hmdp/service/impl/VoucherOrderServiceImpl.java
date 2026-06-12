package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisSeckillUtils;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.redisson.RedissonLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class  VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    private static final String KEY_PREFIX = "order:";
    private static final String queueName = "stream.orders";
    private final ISeckillVoucherService seckillVoucherService;
    private final RedisWorker redisWorker;
    private final RedisSeckillUtils redisSeckillUtils;
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    @Lazy
    @Resource
    private IVoucherOrderService proxy; // 字段注入，@Lazy 防止循环依赖
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    private Boolean running = true;
    @PostConstruct
    private void init()
    {


        try
        {
            stringRedisTemplate.opsForStream().createGroup(queueName,ReadOffset.from("0"), "g1");
        }
        catch (Exception e)
        {
            log.warn("queue and group alrealy have this");

        }
        executorService.submit(new VoucherOrderHandler());

    }
    @PreDestroy
    private  void stopATX()
    {
        this.running = false;
    }
    private class VoucherOrderHandler implements Runnable
    {

        @Override
        public void run()
        {
            while (running)
            {
                try
                {
                    // xread group g1 c1 count 1 block 2000 streams stream.order >
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1)
                                    .block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    if(read == null ||  read.isEmpty()) continue;
                    MapRecord<String, Object, Object> entries = read.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    this.handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());

                }
                catch (Exception e)
                {
                    handlePendingList();
                }


            }
        }
        private void handleVoucherOrder(VoucherOrder voucherOrder)
        {
            RLock rLock = redissonClient.getLock(KEY_PREFIX + voucherOrder.getUserId());
            try
            {
                Boolean isLock = rLock.tryLock();
                if(!isLock) return;
                proxy.createVoucherOrder(voucherOrder);
            }
            finally
            {
                rLock.unlock();
            }

        }
        //死信队列
        private void handlePendingList()
        {
            while(running)
            {
                try
                {
                    // xread group g1 c1 count 1 block 2000 streams stream.order >
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1)
                                    .block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    if(read == null ||  read.isEmpty()) break;
                    MapRecord<String, Object, Object> entries = read.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    proxy.createVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());

                }
                catch (Exception e)
                {
                    log.error("order error:", e);
                }
            }
        }
    };

    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher byId = seckillVoucherService.getById(voucherId);
        if(byId == null) return Result.fail("error ! haven't this vocher!!!");
        if(byId.getBeginTime().isAfter(LocalDateTime.now())) return  Result.fail(" take a moment....");
        if(byId.getEndTime().isBefore(LocalDateTime.now())) return  Result.fail("next come here...");
        if(byId.getStock() < 1) return  Result.fail("haven't number of our stock...");

        // 抢锁（一人一单）：锁 -> 进入事务 -> 一人一单校验 -> 扣库存 -> 下单
        Long userId = UserHolder.getUser().getId();

        //  一人一单
        VoucherOrder voucherOrder = new VoucherOrder();
        long id = redisWorker.nextId("order");

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        int  canSeckill = redisSeckillUtils.canSeckill(voucherId, userId,id);
        switch (canSeckill)
        {
            case 1 :  return Result.fail("stock need to help...");
            case 2 : return  Result.fail("you aleardly have this");
        }


        return Result.ok(id);

    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        // 2. 扣库存：乐观锁 CAS（gt stock 0）兜底超卖，与下单同一事务，失败可回滚
       seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0).update();
        // 3. 创建订单
        save(voucherOrder);
    }

}
