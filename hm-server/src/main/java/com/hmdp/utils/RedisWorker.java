package com.hmdp.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class RedisWorker {
    // 固定的"过去"起始时间戳：2022-01-01 00:00:00 UTC。
    // 必须早于当前时间，否则 timestamp = now - BEGIN 为负，ID 错乱。
    public static final long BEGINTIMESTAPM = 1640995200L;
    private final StringRedisTemplate stringRedisTemplate;
    public long nextId(String prefixKey)
    {
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = second - BEGINTIMESTAPM;
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long increment = stringRedisTemplate.opsForValue().increment("icr:" + prefixKey + ":" + date);
        return timestamp << 32 | increment;
    }

}
