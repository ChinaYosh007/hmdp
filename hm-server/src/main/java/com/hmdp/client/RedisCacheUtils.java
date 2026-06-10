package com.hmdp.client;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisLock;
import jdk.jfr.Description;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@RequiredArgsConstructor
/**
 * * 方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
 * * 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓
 *
 * 存击穿问题
 *
 * * 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
 * * 方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
 */
public class RedisCacheUtils {
    private   final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Boolean tryLock(String key)
    {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,LOCK_SHOP_VALUE,LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    public void unlock(String key)
    {
        stringRedisTemplate.delete(key);
    }
    public void set(String key, Object data,Long time,TimeUnit unit)
    {
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(data),time,unit);
    }
    public <T,ID> void  saveShop2Redis(String keyPrefix,ID id,Function<ID,T> func,Long expriSeconds)
    {
        T json =func.apply(id);
        RedisData redisData = new RedisData();
        redisData.setData(json);
        redisData.setExpireTime(LocalDateTime.from(LocalDateTime.now().plusSeconds(expriSeconds)));
        stringRedisTemplate.opsForValue().set(keyPrefix + id,JSONUtil.toJsonStr(redisData));
    }

    /**
     *
      * @param keyPrefix
     * @param id
     * @param type
     * @param func
     * @param time
     * @param unit
     * @return
     * @param <T>
     * @param <ID>
     *
     *  根据Redis缓存查询，根据前缀和特有的key,拼接得到关键字段，然后根据字段查找数值，主要解决了缓存穿透和雪崩的问题
     *
     *
             */
    public<T,ID> T queryWithPassThrough(String keyPrefix, ID id, Class<T> type, Function<ID,T> func,Long time,TimeUnit unit)
    {
        String key = keyPrefix + id;
        String val = stringRedisTemplate.opsForValue().get(key);
        if(!StrUtil.isBlank(val))
        {
            return JSONUtil.toBean(val,type);
        }
        if(val != null) return null;
        T res = func.apply(id);
        // 如果对应的id不存在，则返回错误
        if(res == null)
        {
            stringRedisTemplate.opsForValue().set(keyPrefix + id,"");
        }
        else
        {
            stringRedisTemplate.opsForValue().set(keyPrefix + id, JSONUtil.toJsonStr(res));
        }

        stringRedisTemplate.expire(keyPrefix + id,time + RandomUtil.randomLong(-5,5) , unit);
        return res;
    }

    /**
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param func
     * @return
     * @param <T>
     * @param <ID>
     *
     *    根据id进行查询和上锁----互斥锁
     *
     */

    public <T,ID> T queryWithMutex(String keyPrefix,ID id,Class<T> type, Function<ID,T> func)
    {
        String key = keyPrefix + id;
        String val = stringRedisTemplate.opsForValue().get(key);
        if(!StrUtil.isBlank(val))
        {
            return JSONUtil.toBean(val,type);
        }
        if(val != null) return null;
        String localKey = LOCK_SHOP_KEY + id;

        Boolean res = tryLock(localKey);
        if(!res)
        {
            try
            {
                Thread.sleep(THREAD_SLEEP_ON_LOCK_TTL);
            } catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
            return queryWithMutex(keyPrefix,id,type,func);
        }

        try
        {
            return   queryWithPassThrough(keyPrefix,id,type,func,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        }
        finally
        {
            unlock(localKey);
        }

    }

    /**
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param func
     * @return
     * @param <T>
     * @param <ID>
     *
     *  @Desc 根据id进行查询和上锁----逻辑锁
     *
     */
    public <T,ID> T  queryWithLogicExpire(String keyPrefix,ID id,Class<T> type, Function<ID,T> func)
    {
        String key = keyPrefix + id;
        String val = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(val))
        {
            return null;
        }
        RedisData data = JSONUtil.toBean(val,RedisData.class);
        T json = JSONUtil.toBean((JSONObject) data.getData(),type);
        LocalDateTime expireTime = data.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now()))
        {
            return json;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        Boolean cur =tryLock(lockKey);
        // 缓存重建
        if(cur)
        {
            CACHE_REBUILD_EXECUTOR.execute(() ->{
                try
                {
                    this.saveShop2Redis(keyPrefix,id,func,20L);
                }
                catch(Exception e)
                {
                    throw  new RuntimeException(e);
                }
                finally {
                    unlock(lockKey);
                }
            });
        }


        return json;
    }



}
