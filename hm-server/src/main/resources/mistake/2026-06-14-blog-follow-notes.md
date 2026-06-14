# 2026-06-14 达人探店 · 关注功能 · 错误总结与知识点

---

## 一、我犯的错误

### 错误 1 — Controller 路由冲突（启动崩溃）

**现象**：应用启动时报 `Ambiguous mapping`，无法启动。

**原因**：把查询用户的方法 `queryUserById` 错误地放进了 `BlogController`，
与 `getInformation` 都映射到 `GET /{id}`，Spring 无法区分。

**根本原因**：没有分清接口归属，用户相关接口应放在 `UserController`，
博客相关接口才放 `BlogController`。

**正确做法**：
- `GET /blog/{id}` → 查博客详情，放 `BlogController`
- `GET /user/{id}` → 查用户信息，放 `UserController`

---

### 错误 2 — StringRedisTemplate 传了 Long 类型（ClassCastException）

**现象**：取关时报 `ClassCastException: Long cannot be cast to String`。

**错误代码**：
```java
stringRedisTemplate.opsForSet().remove(key, target); // target 是 Long
```

**原因**：`StringRedisTemplate` 的序列化器是 `StringRedisSerializer`，
所有 key 和 value 必须是 `String`，传 `Long` 时无法序列化，直接抛出类型异常。

**正确做法**：
```java
stringRedisTemplate.opsForSet().remove(key, target.toString());
```

**记住**：凡是用 `StringRedisTemplate` 操作 Redis，value 一律 `.toString()`。

---

### 错误 3 — 关注时调用了 `isMember` 而非 `add`（逻辑方法写错）

**现象**：关注操作没有写入 Redis，取消关注时也找不到记录。

**错误代码**：
```java
stringRedisTemplate.opsForSet().isMember(key, target.toString()); // 查询，没有写入
```

**原因**：`isMember` 是判断成员是否存在（返回 Boolean），
意图是写入但调用了查询方法，返回值也被丢弃。

**正确做法**：
```java
stringRedisTemplate.opsForSet().add(key, target.toString()); // 写入
```

**记住**：写操作用 `add` / `remove`，判断用 `isMember`，一定看清楚方法语义。

---

### 错误 4 — Java Stream 被消费两次（IllegalStateException）

**现象**：Feed 流接口运行时抛出 `IllegalStateException: stream has already been operated upon or closed`。

**错误代码**：
```java
Stream<Long> ids = typedTuples.stream().map(...);
String join = StrUtil.join(",", ids);       // 第一次消费，Stream 关闭
this.query().in("id", ids)...               // 第二次消费，抛出异常
```

**原因**：Java Stream 是一次性的，terminal operation（如 `forEach`、`collect`、传给工具方法等）
执行后 Stream 即关闭，无法再次使用。

**正确做法**：先 collect 成 List，再复用：
```java
List<Long> ids = typedTuples.stream().map(...).toList(); // 先收集
String join = StrUtil.join(",", ids);   // 可以多次使用 List
this.query().in("id", ids)...
```

**记住**：Stream 只能用一次；需要复用数据，先 `collect(Collectors.toList())` 或 `.toList()`。

---

## 二、今天掌握的知识点

### 知识点 1 — ZSet 实现点赞排行

- 用 ZSet 替代 Set 存储点赞记录，score = `System.currentTimeMillis()`
- 取前 N 名：`opsForZSet().range(key, 0, N-1)`（按 score 升序）
- 优势：天然带时间排序，先点赞的排在前面
- 查询某用户是否点赞：`opsForZSet().score(key, userId)` 返回 null 表示未点赞

```java
// 点赞
stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
// 取消点赞
stringRedisTemplate.opsForZSet().remove(key, userId.toString());
// 是否已点赞
Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
boolean isLiked = score != null;
```

---

### 知识点 2 — Set 实现关注 & 共同关注

- 关注：`opsForSet().add(key, targetId)`
- 取关：`opsForSet().remove(key, targetId)`
- 共同关注（交集）：`opsForSet().intersect(key1, key2)`

```java
// key 设计：follow:{userId} → 存该用户关注的所有人的 id
String key = "follow:" + userId;
// 共同关注
Set<String> commons = stringRedisTemplate.opsForSet().intersect(key1, key2);
```

---

### 知识点 3 — Feed 推送（推模式 / Push）

**推模式**：博主发布博客时，主动推送到所有粉丝的收件箱（ZSet）。

```
发布博客 → 查询所有粉丝 → 遍历粉丝 → opsForZSet().add("feed:{fanId}", blogId, timestamp)
```

- score = 发布时间戳，实现时间线倒序
- 读取时用 `reverseRangeByScoreWithScores`，从大到小（最新的先读）

**推模式 vs 拉模式**：
| | 推模式 | 拉模式 |
|---|---|---|
| 写操作 | 重（每次发博都写 N 个粉丝） | 轻 |
| 读操作 | 轻（直接读收件箱） | 重（合并关注列表再排序） |
| 适合场景 | 粉丝量适中的普通用户 | 大 V（粉丝百万级）|

---

### 知识点 4 — 滚动分页（基于 ZSet score）

普通分页用 `LIMIT offset, size`，问题：新数据插入后 offset 错位。

Feed 流用**基于 score 的滚动分页**：
- 第一次：max = 当前时间戳，offset = 0
- 之后：max = 上次最小 score，offset = 上次最小 score 的重复数量

```java
// Redis 命令等价：ZREVRANGEBYSCORE key max 0 LIMIT offset count
stringRedisTemplate.opsForZSet()
    .reverseRangeByScoreWithScores(key, 0, max, offset, pageSize);
```

返回给前端的数据需要包含：
- `list`：博客列表
- `minTime`：本次结果中最小的时间戳（下次请求的 max）
- `offset`：本次最小时间戳出现的次数（下次请求的 offset，用于跳过重复）

---

### 知识点 5 — 优雅停机（@PreDestroy）

后台线程持有 Redis 连接时，应用关机会先销毁连接工厂，导致线程报 `LettuceConnectionFactory was destroyed`。

**解决方案**：用 `volatile boolean` 标志 + `@PreDestroy` 通知线程停止：

```java
private volatile boolean running = true;

// while (true) 改成
while (running) { ... }

@PreDestroy
public void shutdown() {
    running = false;
    executorService.shutdown();
}
```

**原理**：`volatile` 保证主线程写入对后台线程立即可见（禁止指令重排 + 主内存刷新）。

---

### 知识点 6 — 全局异常处理（@RestControllerAdvice）

`@RestControllerAdvice` + `@ExceptionHandler` 统一处理异常，避免每个 Controller 都写 try-catch。

- Spring 优先匹配**最具体**的异常类型
- 所以把具体异常的处理器写在 `RuntimeException` 之前

```java
@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public Result handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    log.warn("参数类型错误: {} = {}", e.getName(), e.getValue());
    return Result.fail("参数错误：" + e.getName() + " 不合法");
}

@ExceptionHandler(RuntimeException.class) // 兜底
public Result handleRuntimeException(RuntimeException e) {
    log.error(e.toString(), e);
    return Result.fail("服务器异常");
}
```
