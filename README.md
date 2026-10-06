# springboot-cache-engine-java

Spring Boot 工程内的**进程内本地缓存层**，无任何外部服务依赖（不连 Redis/数据库）。

- TTL 过期（读不续命、边界确定）、硬容量上限 + LRU 淘汰
- 未命中回源、同 key 高并发 single-flight（只真正加载一次）
- 加载失败可区分原因（`NOT_FOUND` / `SOURCE_ERROR` / `INTERRUPTED`），底层异常不透出、坏数据不回填
- 带版本号的 JSON 快照落盘/重启恢复，旧版兼容、损坏按原因拒绝
- 详细规则、快照格式与验证方式见 [docs/CACHE.md](docs/CACHE.md)

## 验证

```bash
mvn test
```

运行日志中每个用例输出 `[BEGIN]` / `[PASS]| [FAIL]`（logger：`CACHE-TESTS`），
直接反映覆盖的业务场景与每个用例结果。
