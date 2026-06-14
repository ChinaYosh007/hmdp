# 黑马点评（hmdp）项目踩坑全集 · 复盘总结

> 从初级到中级的完整记录。按模块组织，每条标注「坑 → 为什么错 → 怎么改 / 正确姿势」。
> 配套细分笔记：`seckill-redis-stream-notes.md`（秒杀）、`bloom-filter-notes.md`（布隆过滤器）。

---

## 〇、工程与环境（编译/运行层面，最先踩）

| 坑 | 为什么错 | 正确姿势 |
|----|---------|---------|
| `WebExceptionAdvice` 声明 `package main.java.com.hmdp.config` | 包名 ≠ 物理目录 → 编译失败，且不被组件扫描 | **包名必须等于目录路径**，改为 `package com.hmdp.config` |
| `application.yaml` 里 `redis:` 层级写错 | 配置不生效 | 移到 `spring.data.redis` |
| `application.yaml` 里 `Jackson:` 配置无效 | 大小写/层级错 → 不生效 | 改为 `spring.jackson` |
| 把 `hm-common/target/` 编译产物提交 git | 构建产物每次都会重新生成，污染仓库 | `target/` 加进 `.gitignore`，只提交源码 |
| `.env` 提交到 git | 泄露敏感配置 | `.env` 不入库，`application.yaml` 用 `${KEY:默认值}` 占位 |
| 直接 `spring-boot:run` 报找不到 hm-common | 多模块未先安装本地依赖 | 先 `mvn install`，再 `-pl hm-server spring-boot:run` |
| git 报 `Author identity unknown` | 仓库未配 user.name/email | `git config user.name/email`（与历史提交一致） |

---

## 一、短信登录 / 拦截器

- **双拦截器设计**：RefreshToken 拦截器（拦所有请求，刷新 token TTL + 存 ThreadLocal）+ Login 拦截器（只拦需登录的，做鉴权）。
  - **为什么拆两个**：刷新 TTL 这件事对「未登录也允许访问的页面」也要做，不能塞进鉴权拦截器。
- **ThreadLocal（UserHolder）** 存当前用户：线程隔离，避免方法层层传参。用完要 `remove()` 防内存泄漏。
- **坑：StringRedisTemplate 的值必须是 String**。存 Hash / 对象时，所有字段都要转成 String，否则序列化报错。

---

## 二、商户缓存（三大缓存问题）

- **缓存穿透**：查不存在的数据 → ①缓存空值 ②布隆过滤器（见专门笔记）。
- **缓存雪崩**：大量 key 同时过期 → **随机 TTL** 打散。
- **缓存击穿**：热点 key 过期瞬间被打穿 → **互斥锁** 或 **逻辑过期**。
- **互斥锁的关键坑（封装 `RedisCacheUtils` 时踩的）**：
  1. **锁 key 必须独立于数据 key**（否则锁住的就是数据本身）。
  2. **持锁者才能解锁**（用唯一标识校验，避免误删别人的锁）。
  3. **查库逻辑放进 try-finally**，保证异常时也释放锁。
  4. **拿到锁后 double-check**：再查一次缓存，可能别的线程已重建好。
- **逻辑过期需预热**：逻辑过期不设真实 TTL，数据靠预热提前写入，否则第一次查必落空。
- **Cache Aside**：更新数据时**先改库，再删缓存**（不是改缓存）。
- **`Executor` vs `ExecutorService`、`execute` vs `submit`**：前者无返回值，后者返回 `Future` 能拿结果/异常。

---

## 三、优惠券秒杀（超卖 / 一人一单）

- **全局唯一 ID（RedisWorker）**：`时间戳<<32 | 当日自增`。
  - **坑：起始时间戳必须是过去的某个时间点**，否则算出来的差值是负数。
- **超卖**：用**乐观锁 CAS** —— 扣库存时 `gt("stock", 0)`，库存>0 才扣，不用版本号也能防超卖。
- **一人一单 + 扣库存的执行顺序（最易错）**：
  ```
  抢锁 → 进事务 → 一人一单校验 → 扣库存(CAS) → 下单
  ```
  - **为什么扣库存必须在锁内 + 事务内**：否则同一用户并发请求会重复扣，且事务回滚不了已扣的库存。

---

## 四、分布式锁 / 事务（中级核心）

- **手写 RedisLock**：`SETNX + UUID/线程id 标识 + unlock.lua 原子释放`。
  - **误删问题**：A 的锁超时释放，B 拿到锁，A 执行完直接 del → 删了 B 的锁。
  - **解法**：删之前校验「锁里的标识是不是自己的」，且**校验+删除要原子**（用 Lua，不能 Java 里 if 再 del）。
- **生产用 Redisson**：看门狗自动续期、可重入。
- **`@Transactional` 自调用失效（重灾区）**：
  - **为什么失效**：同类内 `this.方法()` 调用绕过了 Spring 代理，事务/AOP 不生效。
  - **解法**：`@EnableAspectJAutoProxy(exposeProxy = true)` + `AopContext.currentProxy()` 拿到代理再调。
  - **其他失效场景**：方法非 public、异常被 catch 吞掉（没抛出）、非代理对象调用。
- **后台线程调事务方法**：同样要注入 proxy，不能直接 `this.createVoucherOrder()`。

---

## 五、秒杀优化（Lua + 异步下单）

详见 `seckill-redis-stream-notes.md`，要点：
- **Lua 脚本原子判资格**：`SISMEMBER + DECRBY + SADD`，`tonumber` 转换，提前 return 区分 0/1/2 三种结果。
- **orderId 必须在入队前生成**（主线程生成，否则异步线程拿不到一致的 id）。
- **BlockingQueue 用 `take()` 阻塞**，不要 `isEmpty()` 轮询空转（浪费 CPU）。
- **升级 Redis Stream**：启动时 `createGroup` 初始化、消费后 `ACK` 确认、`pending list` 重试兜底。
- **坑：关机时后台线程刷屏报错**（本次新发现）：
  - **现象**：关机时 `LettuceConnectionFactory STOPPING/STOPPED`，后台 read 抛异常被 catch → 调 handlePendingList → 紧贴着再 read 又抛 → 空转刷屏。
  - **根因**：把「连接已关闭」这种**基础设施异常**当成**业务异常**去重试。
  - **解法**：①`running` 加 `volatile`（保证停止信号可见）；②catch 里 `if (!running) break;` 区分关机；③catch 里 `Thread.sleep` 防空转；④`@PreDestroy` 里 `shutdownNow()` 中断阻塞的 read。

---

## 六、达人探店（点赞 / 关注 / Feed）

- **点赞排行**：ZSet，`score = 时间戳`，按 score 排序。
- **共同关注**：Set 的 `intersect` 求交集。
- **Feed 推送（推模式）**：发博时写入所有粉丝的收件箱（ZSet）。
- **滚动分页（避免翻页漂移）**：基于 `score` 而非 offset 翻页 —— `minTime`（上次最小时间）+ `offset`（同分跳过数）。
  - **为什么不用传统分页**：Feed 会动态新增，offset 分页会重复/漏数据。
- **坑：Java Stream 只能消费一次** —— 一个 stream `.collect` 后不能再用，需要重新 `.stream()`。
- **坑：`@RestControllerAdvice` 按异常具体度优先匹配** —— 子类异常 handler 优先于父类，写处理器要注意顺序/粒度。

---

## 七、附近商户（Redis GEO，本次新做）

- **底层是 ZSet**：GeoHash 编码成 score，所以「找附近 + 按距离排序」= ZSet 范围操作。
- **预热**：按 `typeId` 分 key 批量 `GEOADD`（member=shopId，x=经度，y=纬度）。
- **查询**：`opsForGeo().search(FROMLONLAT + BYRADIUS + includeDistance + limit)`。
- **坑清单**：
  1. **`search` vs `radius`**：6.2+ 用 `search`，`radius` 已废弃。
  2. **单位要显式**：`new Distance(5, Metrics.KILOMETERS)`。曾误写 `Metrics.MILES` → 5000 英里 ≈ 8000km，把全国都搜回来了。
  3. **`includeDistance()` 必须加**，否则 `getDistance()` 返回 null → NPE。
  4. **结果本就按距离升序**，不要 `.reversed()`（会变成最远的在最前）。
  5. **GEOSEARCH 不支持 offset 分页** → `limit(end)` 取前 N 个 + Java 端 `stream().skip(from)` 逻辑分页。
  6. **Controller 坐标参数要 `required=false`**，否则不传坐标直接 400，走不到「按 DB 普通分页」的兜底分支。
  7. **分层**：业务逻辑应在 Service，不该在 Controller 里直接 `query()`。
  8. **自注入没必要**：本类内调 `getById` 直接 `this.getById`，别再注入自己（构造器自注入还会触发循环依赖）。
  9. **N+1 查询**：循环里逐条 `getById` 慢 → 应收集 id 一次 `in` 查询；但 `in` 不保证顺序，要 `last("ORDER BY FIELD(id, ...)")` 保序。

---

## 八、用户签到（BitMap，本次新做）

- **签到**：`setBit(key, dayOfMonth - 1, true)`（day1 → offset 0）。
- **统计连续签到**：`bitField GET u{dayOfMonth} 0` 一次取回整月位串 → `while ((num >> cnt & 1) == 1) cnt++` 从最低位数连续的 1。
  - **关键**：Redis 中 offset 0 是**最高位**，所以读出来后 **bit0（最低位）= 今天**，从最低位往上数就是「截止今天的连续天数」。
- **坑：key 格式**（不影响功能但不规范）：
  - `DateTimeFormatter.ofPattern(":yyyyMM")` 开头多了个 `:` → 与 `USER_SIGN_KEY="sign:"` 拼出 `sign::202606`（双冒号）。
  - 月份和 userId 之间无分隔符 → `2026065`。因 yyyyMM 固定 6 位暂不撞 key，但脆弱，建议 `sign:{userId}:{yyyyMM}`。
  - 因 sign() 和 countSign() 拼 key 方式一致，读写匹配所以功能正常。

---

## 九、贯穿全项目的「元教训」

1. **包名必须等于目录路径**（编译期就该排除的低级错）。
2. **Redis key/TTL 集中到 `RedisConstants`**，禁止业务里写裸字符串 key。
3. **DTO 与实体分离**：对外返回 `UserDTO`，绝不把含密码的 `User` 返回前端。
4. **统一返回 `Result`、统一异常处理**（`@RestControllerAdvice`）。
5. **分层纪律**：Controller 只编排校验，业务在 Service，数据访问走 Mapper。
6. **并发先问一句**：「两个请求同时进来会怎样？」—— 超卖、误删锁、重复下单都源于没问这句。
7. **数据结构选型**：String/Hash（对象）、ZSet（排行/Feed）、Set（关注/交集）、BitMap（签到/布隆）、GEO（附近）、HyperLogLog（UV 去重计数）、Stream（消息队列）。
8. **「先操作 DB 还是缓存」「锁和事务谁先谁后」「同步还是异步」**—— 中级工程师的判断力都在这些顺序里。

---

> 完。这个项目把 Redis 的七种数据结构、缓存三大问题、分布式锁、事务失效、秒杀并发、异步削峰都走了一遍。
> 下一个项目见 —— 把这里的「先问并发、先理顺序、先分清职责」带过去。
