package com.hmdp;

import com.hmdp.client.RedisCacheUtils;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisCacheUtils redisCacheUtils;
    @Resource
    private IShopService shopService;

    /**
     * 预热：把 shop:1 写入 Redis（带逻辑过期时间），供逻辑过期方案读取
     */
    @Test
    void preheatShop1() {
        redisCacheUtils.saveShop2Redis(CACHE_SHOP_KEY, 1L, shopService::getById, 1800L);
    }
}
