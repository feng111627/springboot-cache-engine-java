# 本地缓存能力说明（in-process cache）

纯内存、无外部服务依赖的本地缓存层，位于
`com.github.feng111627.springbootcacheenginejava.cache`。核心类型：

| 类型 | 职责 |
| --- | --- |
| `ExpiringCache<K,V>` | TTL 读写、容量上限、LRU 淘汰、统计 |
| `LoadingCache<K,V>` | 未命中回源 + 单飞（single-flight）加载、回填 |
| `CacheSnapshotStore<K,V>` | 带版本号的 JSON 快照保存/恢复 |
| `TimeSource` / `SystemTimeSource` / `MutableTimeSource`（test） | 时钟抽象，便于确定性地测过期 |
| `CacheStats` / `RestoreResult` | 运行统计 / 恢复结果 |
| `CacheLoadException` + `LoadFailureReason` | 加载失败的可区分异常 |
| `SnapshotFormatException` + `SnapshotCorruptReason` | 快照损坏/不兼容的可区分异常 |

## 1. 过期规则（TTL）

- 每条目独立 TTL：`put(key, value, ttlMillis)`，绝对过期时刻 = 写入时 `now + ttl`。
- **边界语义**：`now >= expireAtMillis` 即过期。也就是说 TTL=100 时，第 100ms 起算未命中。
- 过期条目永远不会被 `get` 返回，行为与未命中完全一致，并作为副作用被惰性清理；
  写入导致超容时也会先扫过期条目再做淘汰。
- **读不续命**：命中只更新"最近访问时间"（用于 LRU），不改变 `expireAtMillis`；
  只有显式重新 `put` 才能以新 TTL 续期。
- 不允许写入 `ttlMillis <= 0` 或 `null` 值；恢复路径只接受当前仍存活的条目。

## 2. 淘汰规则（容量 + LRU）

- 硬容量上限 `capacity >= 1`；**任何一次 `put` 返回后都有 `size() <= capacity`**，
  不会出现瞬时超容。
- 超容时的处理顺序：**先清过期条目 → 仍超容则按 LRU 逐个淘汰**，直到回到上限以内。
- LRU 排序：`lastAccessAtMillis` 越旧越先出局；同一毫秒的并列按单调递增的
  写入序号（插入顺序）裁决，保证策略确定、说得清。
- 统计计数器：`hits / misses / expirations / evictions`。

### 并发正确性

- 读路径无锁：recency 写 volatile 字段；清理过期条目使用 `map.remove(key, entry)`
  做实例级 CAS，保证同一条目最多被一个线程计一次过期，不会"读到旧值"。
- 写路径（put/remove/clear）共用一把短临界区的变更锁，在锁内完成插入与超容处理，
  保证容量硬约束与淘汰计数正确；底层仍是 `ConcurrentHashMap`，读不会被写阻塞。
- 因此：高压下**不脏读、不丢更新、不无限膨胀**（单测 8 线程各 2000 次覆盖写 +
  8 线程 16000 次超容混写验证）。

## 3. 并发加载语义（single-flight）

`LoadingCache` 在 `ExpiringCache` 之上提供 load-on-miss：

1. 先查缓存，命中直接返回。
2. 未命中时以 `ConcurrentHashMap<K, CompletableFuture>` 登记在途加载：
   - 第一个线程真正调用 `CacheLoader.load`；
   - 同一 key 的其他并发未命中线程都等待**同一个**结果，回源只发生一次；
   - 不同 key 的加载互不阻塞，可并行。
3. 加载成功后回填缓存（走标准 put，因此同样受 TTL 与容量约束），后续请求命中。
4. **失败不回填**：任何失败都不会把坏数据/空数据写进缓存，下次 miss 重新回源；
   在途条目在 `finally` 中按引用身份移除，不影响该 key 的下一次尝试。

### 失败原因可区分、底层异常不透出

所有失败统一抛 `CacheLoadException`，以 `reason()` 区分：

| 场景 | `LoadFailureReason` |
| --- | --- |
| loader 返回 `null`（下游不存在该 key） | `NOT_FOUND` |
| loader 抛异常 | `SOURCE_ERROR`（原始异常仅作为 `getCause()` 保留用于诊断） |
| 等待/加载过程被中断 | `INTERRUPTED`（中断标记恢复） |

调用方永远不会收到 loader 抛出的原始异常类型。

## 4. 快照格式与兼容范围

`CacheSnapshotStore` 以 UTF-8 JSON、**原子写**（临时文件 + atomic move）落盘。

当前格式版本：`version = 1`，兼容读回范围：**1 … 1**
（常量 `CURRENT_FORMAT_VERSION` / `MIN_SUPPORTED_FORMAT_VERSION`）。

```json
{
  "version": 1,
  "writtenAtMillis": 1728288000000,
  "entries": [
    { "key": "alpha", "value": "AAA", "expireAtMillis": 1728298000000 }
  ]
}
```

- 时间一律使用**绝对 epoch 毫秒**（不是相对 TTL），重启后到期判断仍然正确。
- 恢复是"先整包校验、暂存，再写入"：校验未通过时缓存**完全不变**，不会半量污染。
- 恢复时已过期的条目被跳过并计入 `RestoreResult.expiredSkipped`；
  受容量限制被 LRU 淘汰的计入 `evictedOnRestore`。
- key/value 的类型按构造时给定的 `Class<K>/Class<V>` 反序列化，逐字段校验，
  保证 key 与 value 一一对应、不发生错位。

### 拒绝原因（`SnapshotCorruptReason`，互不混淆）

| 情况 | reason |
| --- | --- |
| 文件读不了 / 不是合法 JSON / 根不是对象 | `UNREADABLE` |
| `version` 高于当前版本，或低于最低兼容版本 | `UNSUPPORTED_VERSION` |
| 缺 `version`、缺 `entries` 或非数组、条目缺 `key/value/expireAtMillis`、时间戳非整数、字段类型与 K/V 不兼容 | `MALFORMED_FIELDS` |
| 同一 key 在快照中出现多次 | `DUPLICATE_KEY` |

损坏数据一律**显式拒绝并给出原因**，不会静默丢弃；只有"快照文件根本不存在"属于
正常的全新启动，返回 `snapshotFound=false` 的 `RestoreResult` 而非抛异常。

> 兼容承诺：当前版本 1 可读 1。未来演进时应保持 `MIN_SUPPORTED_FORMAT_VERSION`
> 只放宽不收紧；出现不兼容字段时升 `version` 并在读取端明确拒绝。

## 5. 怎么验证

无需任何外部服务（不连数据库/Redis），直接：

```bash
mvn test
```

- 三个缓存测试类共 27 个用例，按业务场景用 `@Nested` + 中文 `@DisplayName` 组织：
  - `ExpiringCacheTest`：TTL 边界、读不续命、续期、LRU 淘汰、热 key、容量硬约束、并发写/并发过期读；
  - `LoadingCacheTest`：同 key 单飞、异 key 并行、成功回填、失败不缓存、并发等待者失败、
    NOT_FOUND vs SOURCE_ERROR 可区分、底层异常不透出；
  - `SnapshotStoreTest`：保存/恢复往返、版本号、过期跳过、容量淘汰、旧版兼容、
    未来版本拒绝、UNREADABLE / MALFORMED_FIELDS / DUPLICATE_KEY / 文件不存在。
- 运行日志中每个用例打印一行 `[BEGIN]` 与 `[PASS]/[FAIL]`（logger 名 `CACHE-TESTS`），
  可直接看出覆盖了哪些业务及每个用例结果，例如：

```text
[BEGIN] SingleFlight :: 同 key 并发未命中只回源一次，所有线程拿到同一结果
[PASS]  SingleFlight :: 同 key 并发未命中只回源一次，所有线程拿到同一结果
```

该逐用例日志由 `CaseLoggingExtension`（JUnit ServiceLoader 自动注册，
开关见 `src/test/resources/junit-platform.properties`）输出。

## 6. 最小用法

```java
ExpiringCache<String, User> cache =
        new ExpiringCache<>(10_000, EvictionPolicy.LRU, new SystemTimeSource());

LoadingCache<String, User> loading = new LoadingCache<>(
        cache,
        key -> userRepository.findById(key).orElse(null), // null => NOT_FOUND
        TimeUnit.MINUTES.toMillis(5));

User u = loading.get("u-1"); // 未命中回源一次，并发合并；失败抛 CacheLoadException

CacheSnapshotStore<String, User> snapshots = new CacheSnapshotStore<>(
        Path.of("data/cache.json"), String.class, User.class, cache);
snapshots.save();                 // 停机前落盘（原子写）
RestoreResult r = snapshots.restore(); // 启动恢复；损坏时抛 SnapshotFormatException
```

注：缓存层不依赖 Spring 容器，可独立实例化与测试；如需生命周期管理，可在外层
加一个 `@Component` 封装在启动/停机钩子中调用 `restore()/save()`。
