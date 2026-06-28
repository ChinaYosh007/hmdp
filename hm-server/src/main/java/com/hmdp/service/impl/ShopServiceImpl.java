package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.client.RedisCacheUtils;
import com.hmdp.constants.SystemConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.BloomFilter;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author china yosh
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private  final StringRedisTemplate stringRedisTemplate;
    private  final RedisCacheUtils redisCacheUtils;
    private  final BloomFilter bloomFilter;
    @Override
    public Result queryById(Long id) {
        if (!bloomFilter.contains(id.toString())) {
            return Result.fail("店铺不存在")
                    ;   // 布隆说没有 = 一定没有
        }

        if(id == null || id < 1)
       {
           return Result.fail("店铺id不能为空");
       }
      Shop shop = redisCacheUtils.queryWithMutex(CACHE_SHOP_KEY,id,Shop.class,this::getById);
       return shop == null ?  Result.fail("this shop isn't have!!!"):Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop.getId() == null)  return Result.fail("this id isn't null,sorry...");
        this.updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByGEO(Shop shop, Integer current) {
        if (shop.getX() == null || shop.getY() == null)
        {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", shop.getTypeId())
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int begin = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo()
                .                               search(SHOP_GEO_KEY + shop.getTypeId(),
                                                          GeoReference.fromCoordinate(shop.getX(), shop.getY()), //经纬度
                                                          new Distance(5,Metrics.KILOMETERS),// 距离

                                                          RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().
                                                              includeDistance().limit(end) // 显示距离和限制

                );
        if(search == null) return Result.ok(List.of());
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = search.getContent();
        if(content.isEmpty() || content.size() < begin) return Result.ok(List.of());

        List<Shop> list = content.stream().skip(begin).map(geo -> {
            String name = geo.getContent().getName();
            Shop res = this.getById(Long.valueOf(name));
            res.setDistance(geo.getDistance().getValue());
            return res;
        }).toList();
        return Result.ok(list);
    }


}
