package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
    public static final String KEY_PREFIX = "order:";
    private final ISeckillVoucherService seckillVoucherService;
    private final RedisWorker redisWorker;
    private final RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher byId = seckillVoucherService.getById(voucherId);
        if(byId == null) return Result.fail("error ! haven't this vocher!!!");
        if(byId.getBeginTime().isAfter(LocalDateTime.now())) return  Result.fail(" take a moment....");
        if(byId.getEndTime().isBefore(LocalDateTime.now())) return  Result.fail("next come here...");
        if(byId.getStock() < 1) return  Result.fail("haven't number of our stock...");

        // 抢锁（一人一单）：锁 -> 进入事务 -> 一人一单校验 -> 扣库存 -> 下单
        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock(KEY_PREFIX + userId);
        boolean isLock = lock.tryLock();
        if(!isLock)
        {
           return  Result.fail("you alrealy have one...");
        }
        try
        {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
        finally
        {
            lock.unlock();
        }
    }

    @Transactional
    @Override
    public  Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 1. 一人一单（锁内 + 事务内）
        long count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0) return Result.fail("you alreadly have this voucher...");

        // 2. 扣库存：乐观锁 CAS（gt stock 0）兜底超卖，与下单同一事务，失败可回滚
        boolean ok = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();
        if(!ok) return  Result.fail("haven't number of our stock...");

        // 3. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long id = redisWorker.nextId("order");

        voucherOrder.setId(id);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        return Result.ok(id);
    }
}
