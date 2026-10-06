package com.github.feng111627.springbootcacheenginejava.cache.snapshot;

import com.github.feng111627.springbootcacheenginejava.cache.CacheEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 业务场景：快照损坏或字段不兼容时按可区分理由拒绝，不静默丢弃、不错位恢复。 */
class SnapshotCorruptionTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCorruptionTest.class);

    @TempDir
    Path dir;

    private Path writeValidSnapshot() {
        Path file = dir.resolve("snap.txt");
        new SnapshotStore(file).save(Map.of("k1", new CacheEntry<>("v1", 0L)));
        return file;
    }

    @Test
    @DisplayName("损坏恢复：数据被篡改导致校验和不匹配，按 CORRUPTED 拒绝")
    void tamperedDataRejected() throws Exception {
        log.info("[场景=损坏恢复] 用例= 篡改数据校验和拒绝 - 开始");
        Path file = writeValidSnapshot();
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        lines.set(3, lines.get(3).replace("v1", "v2")); // 篡改数据行，不动校验和
        Files.write(file, lines);

        SnapshotException ex = assertThrows(SnapshotException.class,
                () -> new SnapshotStore(file).load());
        assertEquals(SnapshotRejectReason.CORRUPTED, ex.getReason());
        log.info("[场景=损坏恢复] 用例= 篡改数据校验和拒绝 - 通过, reason={}", ex.getReason());
    }

    @Test
    @DisplayName("损坏恢复：文件被截断（缺校验和行），按 CORRUPTED 拒绝")
    void truncatedFileRejected() throws Exception {
        log.info("[场景=损坏恢复] 用例= 截断文件拒绝 - 开始");
        Path file = writeValidSnapshot();
        List<String> lines = Files.readAllLines(file);
        Files.write(file, lines.subList(0, lines.size() - 1)); // 去掉 checksum 行

        SnapshotException ex = assertThrows(SnapshotException.class,
                () -> new SnapshotStore(file).load());
        assertEquals(SnapshotRejectReason.CORRUPTED, ex.getReason());
        log.info("[场景=损坏恢复] 用例= 截断文件拒绝 - 通过, reason={}", ex.getReason());
    }

    @Test
    @DisplayName("损坏恢复：字段类型不兼容（expireAt 非数字），按 INCOMPATIBLE_FIELD 拒绝")
    void incompatibleFieldRejected() throws Exception {
        log.info("[场景=损坏恢复] 用例= 字段不兼容拒绝 - 开始");
        // 构造一个校验和正确但 expireAt 字段非法的快照
        List<String> data = List.of("key=k\texpireAt=not-a-number\tvalue=v");
        String checksum = sha256Of(data);
        Path file = dir.resolve("bad-field.txt");
        Files.write(file, List.of("#CACHE-SNAPSHOT", "version: 2", "count: 1",
                data.get(0), "checksum: " + checksum));

        SnapshotException ex = assertThrows(SnapshotException.class,
                () -> new SnapshotStore(file).load());
        assertEquals(SnapshotRejectReason.INCOMPATIBLE_FIELD, ex.getReason());
        log.info("[场景=损坏恢复] 用例= 字段不兼容拒绝 - 通过, reason={}", ex.getReason());
    }

    @Test
    @DisplayName("损坏恢复：条目数与声明不符，按 CORRUPTED 拒绝，不错位恢复")
    void countMismatchRejected() throws Exception {
        log.info("[场景=损坏恢复] 用例= 条目数不符拒绝 - 开始");
        List<String> data = List.of("key=a\texpireAt=0\tvalue=1");
        Path file = dir.resolve("bad-count.txt");
        Files.write(file, List.of("#CACHE-SNAPSHOT", "version: 2", "count: 5",
                data.get(0), "checksum: " + sha256Of(data)));

        SnapshotException ex = assertThrows(SnapshotException.class,
                () -> new SnapshotStore(file).load());
        assertEquals(SnapshotRejectReason.CORRUPTED, ex.getReason());
        log.info("[场景=损坏恢复] 用例= 条目数不符拒绝 - 通过, reason={}", ex.getReason());
    }

    @Test
    @DisplayName("损坏恢复：快照文件不存在，按 IO_ERROR 拒绝")
    void missingFileRejected() {
        log.info("[场景=损坏恢复] 用例= 文件不存在 - 开始");
        SnapshotException ex = assertThrows(SnapshotException.class,
                () -> new SnapshotStore(dir.resolve("no-such-file.txt")).load());
        assertEquals(SnapshotRejectReason.IO_ERROR, ex.getReason());
        log.info("[场景=损坏恢复] 用例= 文件不存在 - 通过, reason={}", ex.getReason());
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
