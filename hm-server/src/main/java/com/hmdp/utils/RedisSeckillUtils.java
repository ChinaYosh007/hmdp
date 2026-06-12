package com.hmdp.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RedisSeckillUtils {
    private static final DefaultRedisScript<Integer> CAN_SECKILL;
    private final StringRedisTemplate stringRedisTemplate;
    static
    {
        CAN_SECKILL = new DefaultRedisScript<>();
        CAN_SECKILL.setLocation(new ClassPathResource("can_seckill.lua"));
        CAN_SECKILL.setResultType(Integer.class);
    }

    public int canSeckill(Long seckillId,Long userId,Long id)
    {
        Integer execute = stringRedisTemplate.execute(CAN_SECKILL,
                List.of(RedisConstants.SECKILL_ORDER_PREFIX + seckillId.toString()),
                RedisConstants.SECKILL_STOCK_KEY + seckillId,
                userId.toString(),
                id.toString(),
                seckillId.toString()
        );
        return execute;
    }
}
