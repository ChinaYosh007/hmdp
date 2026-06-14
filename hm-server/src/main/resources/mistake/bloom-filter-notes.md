# 布隆过滤器（Bloom Filter）防缓存穿透 —— 手写实现笔记

> 课程没教的内容，按原理自己造的轮子。记录知识点 + 我踩过的坑 + 为什么这么做。

---

## 一、要解决什么问题：缓存穿透

**缓存穿透**：请求一个**数据库里不存在、Redis 里也不存在**的数据。
每次请求都绕过缓存直击数据库，可以被当成一种**攻击**（构造海量不存在的 id 狂打 DB）。

### 两个朴素方案及其局限
1. **判断 id < 0**：只能挡掉明显非法的 id，挡不住「合法格式但不存在」的 id。
2. **缓存空值**（DB 查不到就往 Redis 写一个空值）：能挡住**同一个**不存在 id 的重复请求，但挡不住**海量不同**的不存在 id —— 每个新 id 都会缓存一个空值，内存会被撑爆。

### 进阶思路（推导过程）
把数据库所有 id 存进一个集合，请求来时先查集合：
- 不在集合 → 直接返回（拦截穿透）
- 在集合 → 放行

但 id 数量可能上亿（11 年左右淘宝商品就超 10 亿），用 List 存太占内存。
→ 用 **bitmap + 哈希** 压缩：id 经哈希算出 bitmap 的某一位，置 1；查询时同样算位，是 0 则一定不存在。
→ 这就是**布隆过滤器**。

---

## 二、布隆过滤器原理

### 核心结构
- 一个很长的**位数组**（bitmap），初始全 0。
- **k 个哈希函数**：每个元素经 k 个哈希算出 k 个位置，全部置 1。
- 查询：算出 k 个位置，**全为 1** 才算「可能存在」；**任意一位为 0** 就「一定不存在」。

### 最重要的特性（务必记牢）

| 判断结果 | 含义 |
|---------|------|
| 布隆说「**不存在**」 | **一定不存在**（无漏判 / 无假阴性） |
| 布隆说「**存在**」 | **可能存在**（有误判 / 假阳性 false positive） |

- **只会误判「有」，绝不会误判「没有」** —— 真实存在的数据绝不会被它拦掉，所以用来防穿透是安全的。
- 代价：**不支持删除**（删一位可能影响别的元素）、**必须提前预热**、布隆里的数据必须 **⊇** 数据库的数据。

### 为什么要 k 个哈希而不是 1 个
单哈希冲突概率太高（随便撞）。k 个哈希同时全撞的概率呈指数级下降，**误判率大幅降低**。

---

## 三、手写实现关键点

```java
private static final long BIT_SIZE  = 1 << 22;  // m：位数组长度，取 2 的幂（≈419 万位≈512KB）
private static final int  HASH_COUNT = 3;        // k：哈希函数个数

private long[] hashIndexes(String value) {
    long[] indexes = new long[HASH_COUNT];
    long hash1 = MurmurHash.hash32(value.getBytes());
    long hash2 = MurmurHash.hash32((value + "salt").getBytes()); // 加盐得到第二个独立哈希
    for (int i = 0; i < HASH_COUNT; i++) {       // i 从 0 跑满 k 次
        long temp = hash1 + i * hash2;           // 双重哈希派生第 i 个位置
        indexes[i] = temp & (BIT_SIZE - 1);      // 一步搞定：取模 + 去负号
    }
    return indexes;
}

public void add(String value) {                  // 预热 / 写时同步调用
    for (long index : hashIndexes(value))
        stringRedisTemplate.opsForValue().setBit(BLOOM_SHOP_KEY, index, true);
}

public boolean contains(String value) {
    for (long index : hashIndexes(value)) {
        Boolean bit = stringRedisTemplate.opsForValue().getBit(BLOOM_SHOP_KEY, index);
        if (!bit) return false;                  // 任意一位为 0 → 一定不存在
    }
    return true;                                 // 全为 1 → 可能存在
}
```

### 双重哈希（Kirsch–Mitzenmacher）
不用真的写 k 个哈希函数。只算 **2 个**基础哈希 h1、h2，用公式 `index_i = h1 + i * h2` 派生出 k 个。
**h1、h2 全程固定不变，只改变 i**。

---

## 四、我踩过的坑（自检清单）

### 1. 把双重哈希写成「链式滚动」
错误写法：循环里 `hash1 = hash2; hash2 = hash3;` 不停覆盖基础哈希。
→ 这是斐波那契式更新，不是双重哈希。**h1、h2 必须固定，只变 i**，每轮把结果存进 `indexes[i]`。

### 2. 循环从 `i = 2` 开始
`for (int i = 2; i < HASH_COUNT; i++)`（HASH_COUNT=3）只跑了一次 i=2，
→ `indexes[0]`、`indexes[1]` 永远是默认值 0，3 个哈希废了 2 个。**必须 `i = 0` 跑满 k 次**。

### 3. 取模取错对象：`% (HASH_COUNT - 1)`
把 **BIT_SIZE（位数组长度，~419 万）** 和 **HASH_COUNT（哈希个数，3）** 搞混了。
`% (HASH_COUNT-1)` = `% 2` → 整个 419 万位的数组只用到第 0、1 两位 → 布隆报废。
**下标必须对 `BIT_SIZE` 收口**。

| 常量 | 含义 | 值 |
|------|------|-----|
| `BIT_SIZE` | 位数组有多长（下标范围） | ~419 万 |
| `HASH_COUNT` | 每个元素占几位 | 3 |

### 4. 负数取模坑：用 `%` 而不是 `&`
哈希可能是负数，Java 里 `负数 % 正数 = 负数` → 负下标 → `setBit(key, 负数)` 报错。
而且 **`Math.abs(Integer.MIN_VALUE)` 仍是负数**（溢出），不能用 Math.abs。
正解：`temp & (BIT_SIZE - 1)`（见下方「为什么用 & 不用 %」）。

### 5. `combined` / `temp` 是什么
只是个临时变量名，存「`h1 + i*h2`」的中间结果。可以写成一行，拆两行只为可读性：
```java
long temp = hash1 + i * hash2;       // 第 1 步：算出大数（可能负 / 超界）
indexes[i] = temp & (BIT_SIZE - 1);  // 第 2 步：压进 [0, BIT_SIZE-1] 且非负
```

### 6. 整数溢出
`i * hash2` 若都是 int 可能溢出。本实现把 hash1/hash2 声明为 `long`，乘法自动按 long 计算，避免溢出。

---

## 五、为什么这么做（设计权衡）

### 为什么 BIT_SIZE 取 2 的幂
2 的幂时 `BIT_SIZE - 1` 的二进制是低位全 1、**符号位为 0**。
任何数 `& (BIT_SIZE-1)` 只保留低位，结果**必落在 `[0, BIT_SIZE-1]` 且非负**。
→ 一个 `&` 同时完成「取模」+「去负号」，比 `%` 快，还顺手解决了负数坑。

### 为什么用 MurmurHash 而不是 String.hashCode()
`hashCode()` 分布差、容易扎堆（相近字符串结果也相近），冲突多；
MurmurHash 打散均匀，**误判率更低**。

### 为什么布隆 + 缓存空值「两道防线」并用
```
请求 → 布隆(挡海量不存在 id) → 缓存空值(兜住布隆放进来的少量误判) → DB
```
- 布隆省内存、能挡海量攻击，但有约百分之几的误判会漏过去。
- 漏过去的少量误判由「缓存空值」兜底，不会反复打 DB。
- 两者互补，是生产常见组合。

### 为什么必须预热 + 写时同步
布隆的逻辑是「不在里面 = 一定不存在 = 拦掉」。
- **没预热**：真实存在的 id 也不在布隆里 → 被全部误拦（假阴性，而布隆本不该有假阴性）。
- **新增数据不同步**：新建商户后忘了 `add` → 用户查这个真实存在的新店 → 被误拦。
→ 所以：启动前预热全部 id；新增商户 `save` 后必须 `bloomFilter.add(...)`。
→ 布隆里的数据必须始终 **⊇** 数据库的数据。

### 为什么预热用 @Test 而不是 @PostConstruct
预热是一次性动作，没必要每次启动都全表扫描；与 GEO 预热保持同一做法。
（生产更讲究会用启动钩子 + 增量更新。）

---

## 六、集成位置

```java
// ShopServiceImpl.queryById 开头，挡在所有缓存/DB 之前
if (!bloomFilter.contains(id.toString())) {
    return Result.fail("店铺不存在");   // 布隆说没有 = 一定没有，直接返回
}
```

测试：预热后查不存在的 id（如 /shop/99999）应被布隆直接拦掉，不打 DB；真实存在的店正常返回。

---

## 七、一句话总结
> 布隆过滤器 = bitmap + k 个哈希；**说没有就一定没有，说有可能误判**；
> 关键实现细节：**2 的幂 + `&` 取模去负号、双重哈希派生 k 个位置、i 从 0 跑满 k 次、对 BIT_SIZE 收口**；
> 关键使用约束：**先预热、写时同步、数据必须 ⊇ 数据库**。
