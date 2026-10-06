package com.github.feng111627.springbootcacheenginejava.cache.snapshot;

import com.github.feng111627.springbootcacheenginejava.cache.CacheEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 业务场景：快照落盘与重启恢复，版本化格式与旧版本兼容读回。 */
class SnapshotCompatTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCompatTest.class);

    @TempDir
    Path dir;

    @Test
    @DisplayName("快照兼容：当前版本保存后可完整读回（重启恢复）")
    void roundTripCurrentVersion() {
        log.info("[场景=快照兼容] 用例= 当前版本往返恢复 - 开始");
        Path file = dir.resolve("snap.txt");
        SnapshotStore store = new SnapshotStore(file);
        Map<String, CacheEntry<String>> entries = new LinkedHashMap<>();
        entries.put("user:1", new CacheEntry<>("张三", 9_999_999L));
        entries.put("user:2", new CacheEntry<>("with\ttab and\nnewline", 0L));
        entries.put("空key测试", new CacheEntry<>("", 123L));

        store.save(entries);
        Map<String, CacheEntry<String>> restored = new SnapshotStore(file).load();

        assertEquals(entries, restored, "保存后读回应完全一致（含特殊字符与空值）");
        log.info("[场景=快照兼容] 用例= 当前版本往返恢复 - 通过, entries={}", restored.size());
    }

    @Test
    @DisplayName("快照兼容：v1 旧版本快照可兼容读回，按永不过期处理")
    void readsLegacyV1Snapshot() throws Exception {
        log.info("[场景=快照兼容] 用例= v1 旧版本兼容读回 - 开始");
        Path file = dir.resolve("snap-v1.txt");
        // 手工构造 v1 格式：key=<enc>\tvalue=<enc>，无 expireAt
        List<String> data = List.of("key=hello", "key=old+key\tvalue=old+value");
        // 修正第一行：v1 每行必须 key=...\tvalue=...
        data = List.of("key=hello\tvalue=world", "key=old+key\tvalue=old+value");
        String checksum = sha256Of(data);
        List<String> lines = new java.util.ArrayList<>();
        lines.add("#CACHE-SNAPSHOT");
        lines.add("version: 1");
        lines.add("count: " + data.size());
        lines.addAll(data);
        lines.add("checksum: " + checksum);
        Files.write(file, lines);

        Map<String, CacheEntry<String>> restored = new SnapshotStore(file).load();
        assertEquals(2, restored.size());
        assertEquals(new CacheEntry<>("world", 0L), restored.get("hello"),
                "v1 条目应按永不过期恢复");
        assertEquals(new CacheEntry<>("old value", 0L), restored.get("old key"));
        log.info("[场景=快照兼容] 用例= v1 旧版本兼容读回 - 通过");
    }

    @Test
    @DisplayName("快照兼容：高于当前版本的快照按 UNSUPPORTED_VERSION 拒绝")
    void rejectsNewerVersion() throws Exception {
        log.info("[场景=快照兼容] 用例= 高版本拒绝 - 开始");
        Path file = dir.resolve("snap-v99.txt");
        Files.write(file, List.of(
                "#CACHE-SNAPSHOT", "version: 99", "count: 0", "checksum: "));

        SnapshotException ex = assertThrows(SnapshotException.class,
                () -> new SnapshotStore(file).load());
        assertEquals(SnapshotRejectReason.UNSUPPORTED_VERSION, ex.getReason());
        log.info("[场景=快照兼容] 用例= 高版本拒绝 - 通过, reason={}", ex.getReason());
    }

    @Test
    @DisplayName("快照兼容：恢复进 LocalCache 后过期条目不复活")
    void restoreSkipsExpiredEntries() {
        log.info("[场景=快照兼容] 用例= 过期条目不复活 - 开始");
        Path file = dir.resolve("snap-exp.txt");
        SnapshotStore store = new SnapshotStore(file);
        long now = System.currentTimeMillis();
        Map<String, CacheEntry<String>> entries = Map.of(
                "alive", new CacheEntry<>("a", now + 60_000),
                "dead", new CacheEntry<>("d", now - 1));
        store.save(entries);

        com.github.feng111627.springbootcacheenginejava.cache.LocalCache<String, String> cache =
                new com.github.feng111627.springbootcacheenginejava.cache.LocalCache<>(10);
        cache.restoreEntries(store.load());
        assertTrue(cache.get("alive").isPresent(), "未过期条目应恢复");
        assertTrue(cache.get("dead").isEmpty(), "已过期条目不得复活");
        log.info("[场景=快照兼容] 用例= 过期条目不复活 - 通过");
    }

    private static String sha256Of(List<String> lines) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        for (String line : lines) {
            md.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }
}
