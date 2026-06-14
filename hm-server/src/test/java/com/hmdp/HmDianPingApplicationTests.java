package com.hmdp;

import com.hmdp.client.RedisCacheUtils;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.BloomFilter;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisCacheUtils redisCacheUtils;
    @Resource
    private IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private BloomFilter bloomFilter;

    /**
     * 预热：
     */
    @Test
    void shop_address()
    {
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> collect = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for(var entry : collect.entrySet())
        {
                Long typeId = entry.getKey();
                String key = RedisConstants.SHOP_GEO_KEY + typeId;
                var shops = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> location = shops.stream().map(shop -> {
                return new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY()));
            }).toList();

            stringRedisTemplate.opsForGeo().add(key,location);
        }


    }

    /**
     * 布隆过滤器预热：把数据库里现有的所有 shop id 灌进布隆，
     * 之后查询不存在的 id 才能在布隆这一层被直接拦掉（防缓存穿透）。
     */
    @Test
    void bloom_warmup()
    {
        List<Shop> list = shopService.list();
        for (Shop shop : list) {
            bloomFilter.add(shop.getId().toString());
        }
    }
}
