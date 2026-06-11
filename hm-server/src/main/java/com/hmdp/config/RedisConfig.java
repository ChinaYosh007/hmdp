package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
public class RedisConfig {
    @Value("${spring.data.redis.host}")
    private  String URL;
    @Value("${spring.data.redis.port}")
    private  String PORT;
    @Value("${spring.data.redis.password}")
    private String PWD;
    @Value("${spring.data.redis.database:0}")
    private int DATABASE;
    @Bean
    public RedissonClient redissonClient()
    {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + URL + ":" + PORT)
                .setPassword(PWD)
                // 与 spring.data.redis.database 保持一致，分布式锁和缓存落在同一个库
                .setDatabase(DATABASE)
                // 远程 Redis 链路不稳定：把启动时预建连接降到最低，放宽超时并增加重试，避免启动期连接初始化失败
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                .setConnectTimeout(15000)
                .setTimeout(5000)
                .setRetryAttempts(5)
                .setRetryInterval(2000);
        return Redisson.create(config);
    }
}
