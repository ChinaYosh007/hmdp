package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class RedisLock {
    private static final String KEY_PREFIX = "lock:";
    // JVM 级前缀，区分不同进程；拼上线程 id 作为锁的唯一标识，保证"谁加锁谁解锁"
    private static final String ID_PREFIX = STR."\{UUID.randomUUID().toString(true)}-";
    private final StringRedisTemplate stringRedisTemplate;
    private final String name;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public RedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Boolean tryLock(long timeoutSec) {
        String id = ID_PREFIX + Thread.currentThread().threadId();
        Boolean res = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, id, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(res);
    }

    // 用 Lua 保证"判断是不是自己的锁 + 删除"两步原子执行，避免误删别人的锁
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().threadId()
        );
    }
}
