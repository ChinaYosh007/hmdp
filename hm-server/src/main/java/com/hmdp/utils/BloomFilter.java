package com.hmdp.utils;

import cn.hutool.core.lang.hash.MurmurHash;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BloomFilter {
    private final StringRedisTemplate stringRedisTemplate;

    private static final long BIT_SIZE = 1 << 22;   // m，位数组长度（2 的幂）
    private static final int HASH_COUNT = 3;         // k，哈希函数个数

    /** 计算一个 value 对应的 k 个 bit 下标 */
    private long[] hashIndexes(String value) {
        long[] indexes = new long[HASH_COUNT];
        long  hash1 = MurmurHash.hash32(value.getBytes());
        long hash2 = MurmurHash.hash32((value + "I love you").getBytes());
        for(int i = 0; i < HASH_COUNT; i++)
        {
            long temp = hash1 + i * hash2;
            indexes[i] = temp & (BIT_SIZE - 1);
        }

        return indexes;
    }

    /** 加入元素（预热时调用） */
    public void add(String value) {
        // TODO: 遍历 hashIndexes(value)，每个 setBit(BLOOM_SHOP_KEY, index, true)
        var nums = this.hashIndexes(value);
        for(int i = 0; i < nums.length; i++)
        stringRedisTemplate.opsForValue().setBit(RedisConstants.BLOOM_SHOP_KEY,nums[i],true);
    }

    /** 判断元素是否「可能存在」 */
    public boolean contains(String value) {
        long[] nums = hashIndexes(value);
        for(int i = 0; i < nums.length; i++)
        {
            Boolean bit = stringRedisTemplate.opsForValue().getBit(RedisConstants.BLOOM_SHOP_KEY, nums[i]);
            if(!bit) return false;

        }

        return true;
    }
}
