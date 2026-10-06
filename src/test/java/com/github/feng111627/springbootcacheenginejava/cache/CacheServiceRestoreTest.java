package com.github.feng111627.springbootcacheenginejava.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 业务场景：服务停机落盘、重启恢复；快照损坏时服务仍可从空缓存启动。 */
class CacheServiceRestoreTest {

    private static final Logger log = LoggerFactory.getLogger(CacheServiceRestoreTest.class);

    @TempDir
    Path dir;

    @Test
    @DisplayName("重启恢复：停机落盘后新实例启动可恢复数据")
    void restartRestoresData() {
        log.info("[场景=重启恢复] 用例= 停机落盘重启恢复 - 开始");
        Path file = dir.resolve("svc-snap.txt");

        CacheService first = new CacheService(100, file.toString());
        first.restore();
        first.cache().put("order:1", "paid", 60_000);
        first.cache().put("order:2", "shipped", 60_000);
        first.persist(); // 模拟停机

        CacheService second = new CacheService(100, file.toString());
        second.restore(); // 模拟重启
        assertEquals("paid", second.cache().get("order:1").orElseThrow());
        assertEquals("shipped", second.cache().get("order:2").orElseThrow());
        log.info("[场景=重启恢复] 用例= 停机落盘重启恢复 - 通过");
    }

    @Test
    @DisplayName("重启恢复：快照损坏时服务降级为空缓存启动，不崩溃")
    void corruptedSnapshotDegradesToEmptyCache() throws Exception {
        log.info("[场景=重启恢复] 用例= 损坏快照降级启动 - 开始");
        Path file = dir.resolve("corrupt-snap.txt");
        Files.write(file, List.of("this is not a snapshot at all"));

        CacheService service = new CacheService(100, file.toString());
        service.restore(); // 不应抛异常
        assertEquals(0, service.cache().size(), "快照损坏时应从空缓存启动");

        // 服务仍可正常读写
        service.cache().put("k", "v", 60_000);
        assertEquals("v", service.cache().get("k").orElseThrow());
        log.info("[场景=重启恢复] 用例= 损坏快照降级启动 - 通过");
    }

    @Test
    @DisplayName("重启恢复：首次启动无快照文件时正常启动")
    void firstBootWithoutSnapshot() {
        log.info("[场景=重启恢复] 用例= 无快照首次启动 - 开始");
        CacheService service = new CacheService(100, dir.resolve("none.txt").toString());
        service.restore();
        assertEquals(0, service.cache().size());
        log.info("[场景=重启恢复] 用例= 无快照首次启动 - 通过");
    }
}
