package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private   final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
       if(id == null || id < 1)
       {
           return Result.fail("店铺id不能为空");
       }
        Object val = stringRedisTemplate.opsForHash().get(CACHE_SHOP_KEY + id, id.toString());
        if(val != null) {
            if (((String) val).isEmpty()) return Result.fail("this shop isn't have of our");
            return Result.ok(JSONUtil.toBean(new String((String) val),Shop.class));
        }
        Shop shop = getById(id);
        // 如果对应的id不存在，则返回错误
        if(shop == null)
        {
            stringRedisTemplate.opsForHash().put(CACHE_SHOP_KEY + id,id.toString(),"");

        }
        else
        {
            stringRedisTemplate.opsForHash().put(CACHE_SHOP_KEY + id,id.toString(), JSONUtil.toJsonStr(shop));

        }
        stringRedisTemplate.expire(CACHE_SHOP_KEY + id,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop == null ?  Result.fail("this shop isn't have of our") :Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop.getId() == null)  return Result.fail("this id isn't null,sorry...");
        this.updateById(shop);
        stringRedisTemplate.opsForHash().delete(CACHE_SHOP_KEY + shop.getId(),shop.getId().toString());
        return Result.ok();
    }
}
