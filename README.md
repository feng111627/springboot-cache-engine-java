# 进程内本地缓存层

一套可独立验证的进程内缓存能力，不依赖任何外部服务，用于挡住对下游的重复读取。

## 组件

| 类 | 职责 |
|---|---|
| `cache.LocalCache` | TTL 过期 + LRU 淘汰 + 单飞回源加载的核心缓存，线程安全 |
| `cache.CacheLoader` / `CacheLoadException` / `LoadFailureReason` | 回源加载契约与失败封装 |
| `cache.snapshot.SnapshotStore` | 版本化快照落盘与恢复 |
| `cache.snapshot.SnapshotException` / `SnapshotRejectReason` | 快照拒绝原因 |
| `cache.CacheService` | Spring 装配：启动恢复快照、停机落盘 |

配置项（`application.properties`）：

- `cache.max-capacity`：容量上限，默认 1000
- `cache.snapshot-file`：快照文件路径，默认 `./cache-snapshot.txt`

## 过期与淘汰规则

- **过期**：`put(key, value, ttlMillis)` 写入时记录绝对过期时间；`ttlMillis <= 0` 表示永不过期。
  读取时惰性判断，过期即视为未命中并移除，过期数据永远读不到。覆盖写入按新 TTL 重新计时。
- **淘汰**：容量有硬上限（`maxCapacity`）。超出时按 **LRU（最近最少使用）** 淘汰最冷条目，
  `get` 命中会刷新热度。任何时刻 `size() <= maxCapacity`，高压/并发写入下容量恒定不膨胀。
- **并发正确性**：内部以锁保护访问序 LinkedHashMap，读写互斥，不会脏读、不会丢更新。

## 并发加载语义

`getOrLoad(key, ttlMillis, loader)`：

- 命中直接返回，不回源。
- 未命中时**单飞（single-flight）**：同一 key 无论多少线程同时未命中，只真正执行一次
  `loader.load(key)`，其余线程等待同一个结果并拿到相同的值。
- 加载成功后回填缓存（带 TTL）。
- **加载失败不缓存**：坏数据不会留在缓存里，下次调用会重新回源。
- 失败统一封装为 `CacheLoadException`，底层异常只作为 cause 保留、不原样透出；
  通过 `getReason()` 区分失败原因：

  | `LoadFailureReason` | 触发条件 |
  |---|---|
  | `TIMEOUT` | 底层抛 `TimeoutException` |
  | `SOURCE_UNAVAILABLE` | 底层抛 `IOException`（下游不可用） |
  | `REJECTED` | 底层抛 `IllegalArgumentException`/`SecurityException`，或 loader 返回 null |
  | `UNKNOWN` | 其他未分类异常、加载线程被中断 |

## 快照格式与兼容范围

UTF-8 文本格式，带版本号与 SHA-256 校验和：

```
#CACHE-SNAPSHOT
version: 2
count: N
key=<url编码>\texpireAt=<毫秒时间戳,0=永不过期>\tvalue=<url编码>
... 共 N 行 ...
checksum: <对所有数据行的 SHA-256>
```

- **当前写入版本：v2**（含 `expireAt`）。
- **兼容读回：v1**（无 `expireAt` 字段，恢复时按永不过期处理）。
- 落盘为原子写（临时文件 + move），不会产生半截文件。
- 恢复时已过期条目不会复活；恢复后仍受容量上限约束。
- 拒绝行为：任何不满足约束的快照都抛 `SnapshotException` 并携带可区分原因，
  **不静默丢弃、不错位恢复**：

  | `SnapshotRejectReason` | 触发条件 |
  |---|---|
  | `CORRUPTED` | 头部非法、校验和不匹配、条目数与声明不符、文件被截断 |
  | `UNSUPPORTED_VERSION` | 版本号超出支持范围 `[1, 2]` |
  | `INCOMPATIBLE_FIELD` | 字段结构与该版本约定不符（如 `expireAt` 非数字） |
  | `IO_ERROR` | 文件不存在或不可读写 |

`CacheService` 启动时恢复快照：快照缺失或损坏只记录警告日志（含原因），
服务降级为空缓存启动，不影响可用性；停机（`@PreDestroy`）时落盘未过期条目。

## 如何验证

```bash
mvn test
```

单测按业务场景组织，每个用例的日志都会打印 `[场景=xxx] 用例= ... 开始/通过`：

| 测试类 | 覆盖场景 |
|---|---|
| `CacheExpiryTest` | TTL 内可读/到期不可读、永不过期、覆盖写刷新过期 |
| `CacheEvictionTest` | LRU 淘汰最冷条目、10 万级高压写入容量恒定、16 线程并发读写不膨胀 |
| `CacheConcurrentLoadTest` | 32 线程同 key 只加载一次、失败不缓存可重试、四种失败原因区分、命中不回源 |
| `SnapshotCompatTest` | 当前版本往返恢复（含特殊字符/空值）、v1 旧版本兼容读回、高版本拒绝、过期条目不复活 |
| `SnapshotCorruptionTest` | 篡改数据/截断/条目数不符按 CORRUPTED 拒绝、字段不兼容按 INCOMPATIBLE_FIELD 拒绝、缺文件按 IO_ERROR 拒绝 |
| `CacheServiceRestoreTest` | 停机落盘重启恢复、损坏快照降级空缓存启动、无快照首次启动 |
