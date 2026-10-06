package com.github.feng111627.springbootcacheenginejava.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("快照落盘与恢复 (Snapshot Persistence)")
class SnapshotStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("snapshot.json");
    }

    private ExpiringCache<String, String> newCache(MutableTimeSource clock, int capacity) {
        return new ExpiringCache<>(capacity, EvictionPolicy.LRU, clock);
    }

    private CacheSnapshotStore<String, String> store(Path f,
                                                     MutableTimeSource clock,
                                                     ExpiringCache<String, String> cache) {
        return new CacheSnapshotStore<>(f, String.class, String.class, cache,
                new ObjectMapper(), clock);
    }

    @Nested
    @DisplayName("保存/恢复往返")
    class RoundTrip {
        @Test
        @DisplayName("保存后重启可恢复全部未过期数据，值不错位")
        void saveAndRestore() {
            MutableTimeSource clock = new MutableTimeSource(1_000_000);
            var old = newCache(clock, 16);
            old.put("alpha", "AAA", 10_000);
            old.put("beta", "BBB", 20_000);
            clock.advance(5_000);
            Path f = file();
            store(f, clock, old).save();

            // 模拟重启：全新缓存实例，时钟延续
            var rebooted = newCache(clock, 16);
            RestoreResult result = store(f, clock, rebooted).restore();

            assertTrue(result.snapshotFound());
            assertEquals(2, result.entriesRead());
            assertEquals(2, result.entriesRestored());
            assertEquals("AAA", rebooted.get("alpha").orElseThrow(), "key/value 必须正确对应");
            assertEquals("BBB", rebooted.get("beta").orElseThrow(), "key/value 不能错位");
        }

        @Test
        @DisplayName("快照文件带当前版本号")
        void snapshotHasVersion() throws Exception {
            MutableTimeSource clock = new MutableTimeSource(0);
            var cache = newCache(clock, 4);
            cache.put("k", "v", 1_000);
            Path f = file();
            store(f, clock, cache).save();

            ObjectNode root = (ObjectNode) new ObjectMapper().readTree(Files.readAllBytes(f));
            assertEquals(CacheSnapshotStore.CURRENT_FORMAT_VERSION,
                    root.get("version").asInt(), "快照必须带版本号");
            assertTrue(root.has("writtenAtMillis"));
            assertEquals(1, root.get("entries").size());
        }

        @Test
        @DisplayName("恢复时已过期条目被跳过并计数，不进入缓存")
        void expiredEntriesSkippedOnRestore() {
            MutableTimeSource clock = new MutableTimeSource(0);
            var old = newCache(clock, 16);
            old.put("live", "L", 10_000);
            old.put("dead", "D", 100);
            Path f = file();
            store(f, clock, old).save();

            clock.advance(500); // dead 已过期，live 仍存活
            var rebooted = newCache(clock, 16);
            RestoreResult result = store(f, clock, rebooted).restore();

            assertEquals(2, result.entriesRead());
            assertEquals(1, result.expiredSkipped());
            assertEquals(1, result.entriesRestored());
            assertEquals("L", rebooted.get("live").orElseThrow());
            assertTrue(rebooted.get("dead").isEmpty(), "过期数据不得恢复");
        }

        @Test
        @DisplayName("恢复条目超过容量时按 LRU 淘汰并计数，文件里的存活总数不变")
        void restoreRespectsCapacity() {
            MutableTimeSource clock = new MutableTimeSource(0);
            var old = newCache(clock, 8);
            for (int i = 0; i < 8; i++) {
                old.put("k" + i, "v" + i, 1_000_000);
            }
            Path f = file();
            store(f, clock, old).save();

            var rebooted = newCache(clock, 3);
            RestoreResult result = store(f, clock, rebooted).restore();
            assertEquals(3, rebooted.size(), "恢复后仍受容量约束");
            assertEquals(5, result.evictedOnRestore());
        }
    }

    @Nested
    @DisplayName("版本兼容")
    class Compatibility {
        @Test
        @DisplayName("旧版本(version=1)快照可兼容读回")
        void oldVersionIsReadable() throws Exception {
            Path f = file();
            writeSnapshot(f, 1, new Object[][]{
                    {"legacy", "still-good", 5_000_000L}
            }, 0L);

            MutableTimeSource clock = new MutableTimeSource(1_000);
            var cache = newCache(clock, 4);
            RestoreResult result = store(f, clock, cache).restore();
            assertEquals(1, result.entriesRestored());
            assertEquals("still-good", cache.get("legacy").orElseThrow());
        }

        @Test
        @DisplayName("更高版本快照按 UNSUPPORTED_VERSION 拒绝，缓存保持为空")
        void futureVersionRejected() throws Exception {
            Path f = file();
            writeSnapshot(f, 99, new Object[][]{
                    {"future", "x", 5_000_000L}
            }, 0L);

            MutableTimeSource clock = new MutableTimeSource(1_000);
            var cache = newCache(clock, 4);
            SnapshotFormatException ex = assertThrows(SnapshotFormatException.class,
                    () -> store(f, clock, cache).restore());
            assertEquals(SnapshotCorruptReason.UNSUPPORTED_VERSION, ex.reason());
            assertEquals(0, cache.size(), "拒绝恢复时不得有任何数据进入缓存");
        }
    }

    @Nested
    @DisplayName("损坏恢复")
    class Corruption {
        @Test
        @DisplayName("非 JSON 内容按 UNREADABLE 拒绝")
        void garbageRejected() throws Exception {
            Path f = file();
            Files.writeString(f, "this is { not valid json ] garbage");
            MutableTimeSource clock = new MutableTimeSource(0);
            var cache = newCache(clock, 4);
            SnapshotFormatException ex = assertThrows(SnapshotFormatException.class,
                    () -> store(f, clock, cache).restore());
            assertEquals(SnapshotCorruptReason.UNREADABLE, ex.reason());
        }

        @Test
        @DisplayName("缺版本号按 MALFORMED_FIELDS 拒绝")
        void missingVersionRejected() throws Exception {
            Path f = file();
            String json = """
                    {"writtenAtMillis":1,"entries":[]}
                    """;
            Files.writeString(f, json);
            MutableTimeSource clock = new MutableTimeSource(0);
            var cache = newCache(clock, 4);
            SnapshotFormatException ex = assertThrows(SnapshotFormatException.class,
                    () -> store(f, clock, cache).restore());
            assertEquals(SnapshotCorruptReason.MALFORMED_FIELDS, ex.reason());
        }

        @Test
        @DisplayName("缺 key / 缺 expireAtMillis / 类型不兼容按 MALFORMED_FIELDS 拒绝")
        void badFieldsRejected() throws Exception {
            MutableTimeSource clock = new MutableTimeSource(0);

            assertBadField(clock, """
                    {"version":1,"entries":[{"value":"v","expireAtMillis":9999999999}]}
                    """, "缺少 key");
            assertBadField(clock, """
                    {"version":1,"entries":[{"key":"k","value":"v"}]}
                    """, "缺少 expireAtMillis");
            assertBadField(clock, """
                    {"version":"oops","entries":[]}
                    """, "version 类型错误");
            assertBadField(clock, """
                    {"version":1,"entries":[{"key":{"nested":1},"value":"v","expireAtMillis":9999999999}]}
                    """, "key 为对象、与 K=String 不兼容");
            assertBadField(clock, """
                    {"version":1,"entries":"not-an-array"}
                    """, "entries 不是数组");
        }

        @Test
        @DisplayName("重复 key 按 DUPLICATE_KEY 拒绝，防止恢复错位")
        void duplicateKeyRejected() throws Exception {
            Path f = file();
            writeSnapshot(f, 1, new Object[][]{
                    {"dup", "first", 9_000_000_000L},
                    {"dup", "second", 9_000_000_000L}
            }, 0L);
            MutableTimeSource clock = new MutableTimeSource(1_000);
            var cache = newCache(clock, 4);
            SnapshotFormatException ex = assertThrows(SnapshotFormatException.class,
                    () -> store(f, clock, cache).restore());
            assertEquals(SnapshotCorruptReason.DUPLICATE_KEY, ex.reason());
            assertEquals(0, cache.size());
        }

        @Test
        @DisplayName("快照文件不存在时返回未找到而非抛错")
        void missingFileReturnsNotFound() {
            MutableTimeSource clock = new MutableTimeSource(0);
            var cache = newCache(clock, 4);
            RestoreResult result = store(dir.resolve("never-existed.json"), clock, cache).restore();
            assertEquals(false, result.snapshotFound());
            assertEquals(0, result.entriesRead());
        }

        private void assertBadField(MutableTimeSource clock, String json, String label)
                throws Exception {
            Path f = dir.resolve("bad-" + System.nanoTime() + ".json");
            Files.writeString(f, json);
            var cache = newCache(clock, 4);
            SnapshotFormatException ex = assertThrows(SnapshotFormatException.class,
                    () -> store(f, clock, cache).restore(), label);
            assertEquals(SnapshotCorruptReason.MALFORMED_FIELDS, ex.reason(), label);
            assertEquals(0, cache.size(), label);
        }
    }

    /** Hand-crafts a snapshot so tests are independent of the current writer. */
    private void writeSnapshot(Path f, int version, Object[][] rows, long writtenAt)
            throws Exception {
        ObjectMapper m = new ObjectMapper();
        ObjectNode root = m.createObjectNode();
        root.put("version", version);
        root.put("writtenAtMillis", writtenAt);
        ArrayNode entries = root.putArray("entries");
        for (Object[] row : rows) {
            ObjectNode en = entries.addObject();
            en.put("key", (String) row[0]);
            en.put("value", (String) row[1]);
            en.put("expireAtMillis", (Long) row[2]);
        }
        Files.write(f, m.writeValueAsBytes(root));
    }
}
