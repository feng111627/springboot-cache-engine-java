package com.github.feng111627.springbootcacheenginejava.cache.snapshot;

import com.github.feng111627.springbootcacheenginejava.cache.CacheEntry;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存快照落盘与恢复。
 *
 * <p>格式（UTF-8 文本，行结构）：</p>
 * <pre>
 * #CACHE-SNAPSHOT
 * version: 2
 * count: N
 * key=&lt;urlenc&gt;\texpireAt=&lt;millis,0=永不过期&gt;\tvalue=&lt;urlenc&gt;
 * ...
 * checksum: &lt;对以上数据行的 SHA-256&gt;
 * </pre>
 *
 * <p>兼容范围：当前写入 v2；可兼容读回 v1（无 expireAt 字段，按永不过期处理）。
 * 版本高于当前、校验和不匹配、字段不兼容分别按不同 {@link SnapshotRejectReason} 拒绝，
 * 绝不静默丢弃或错位恢复。</p>
 */
public class SnapshotStore {

    public static final int CURRENT_VERSION = 2;
    public static final int MIN_SUPPORTED_VERSION = 1;

    private static final String MAGIC = "#CACHE-SNAPSHOT";

    private final Path file;

    public SnapshotStore(Path file) {
        this.file = file;
    }

    /** 原子落盘：先写临时文件再移动，避免半截文件。 */
    public void save(Map<String, CacheEntry<String>> entries) {
        List<String> dataLines = new ArrayList<>();
        for (Map.Entry<String, CacheEntry<String>> e : entries.entrySet()) {
            dataLines.add("key=" + enc(e.getKey())
                    + "\texpireAt=" + e.getValue().expireAtMillis()
                    + "\tvalue=" + enc(e.getValue().value()));
        }
        List<String> lines = new ArrayList<>();
        lines.add(MAGIC);
        lines.add("version: " + CURRENT_VERSION);
        lines.add("count: " + dataLines.size());
        lines.addAll(dataLines);
        lines.add("checksum: " + sha256(dataLines));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new SnapshotException(SnapshotRejectReason.IO_ERROR,
                    "failed to write snapshot " + file, e);
        }
    }

    /** 读回快照；任何不满足约束的情况都抛出带原因的 {@link SnapshotException}。 */
    public Map<String, CacheEntry<String>> load() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SnapshotException(SnapshotRejectReason.IO_ERROR,
                    "failed to read snapshot " + file, e);
        }
        if (lines.size() < 4 || !lines.get(0).equals(MAGIC)) {
            throw new SnapshotException(SnapshotRejectReason.CORRUPTED,
                    "bad snapshot header in " + file);
        }
        int version = parseIntField(lines.get(1), "version: ");
        if (version < MIN_SUPPORTED_VERSION || version > CURRENT_VERSION) {
            throw new SnapshotException(SnapshotRejectReason.UNSUPPORTED_VERSION,
                    "snapshot version " + version + " not in supported range ["
                            + MIN_SUPPORTED_VERSION + ", " + CURRENT_VERSION + "]");
        }
        int count = parseIntField(lines.get(2), "count: ");
        String checksumLine = lines.get(lines.size() - 1);
        if (!checksumLine.startsWith("checksum: ")) {
            throw new SnapshotException(SnapshotRejectReason.CORRUPTED,
                    "missing checksum line in " + file);
        }
        List<String> dataLines = lines.subList(3, lines.size() - 1);
        if (dataLines.size() != count) {
            throw new SnapshotException(SnapshotRejectReason.CORRUPTED,
                    "entry count mismatch: declared " + count + ", actual " + dataLines.size());
        }
        String expected = checksumLine.substring("checksum: ".length());
        if (!sha256(dataLines).equals(expected)) {
            throw new SnapshotException(SnapshotRejectReason.CORRUPTED,
                    "checksum mismatch in " + file);
        }
        Map<String, CacheEntry<String>> result = new LinkedHashMap<>();
        for (String line : dataLines) {
            String[] parts = line.split("\t", -1);
            if (version == 1) {
                // v1: key=<enc>\tvalue=<enc>，无过期时间，按永不过期恢复
                if (parts.length != 2 || !parts[0].startsWith("key=")
                        || !parts[1].startsWith("value=")) {
                    throw new SnapshotException(SnapshotRejectReason.INCOMPATIBLE_FIELD,
                            "v1 entry malformed: " + line);
                }
                result.put(dec(parts[0].substring(4)),
                        new CacheEntry<>(dec(parts[1].substring(6)), 0L));
            } else {
                // v2: key=<enc>\texpireAt=<long>\tvalue=<enc>
                if (parts.length != 3 || !parts[0].startsWith("key=")
                        || !parts[1].startsWith("expireAt=")
                        || !parts[2].startsWith("value=")) {
                    throw new SnapshotException(SnapshotRejectReason.INCOMPATIBLE_FIELD,
                            "v2 entry malformed: " + line);
                }
                long expireAt;
                try {
                    expireAt = Long.parseLong(parts[1].substring(9));
                } catch (NumberFormatException e) {
                    throw new SnapshotException(SnapshotRejectReason.INCOMPATIBLE_FIELD,
                            "expireAt not a number: " + parts[1]);
                }
                result.put(dec(parts[0].substring(4)),
                        new CacheEntry<>(dec(parts[2].substring(6)), expireAt));
            }
        }
        return result;
    }

    private int parseIntField(String line, String prefix) {
        if (!line.startsWith(prefix)) {
            throw new SnapshotException(SnapshotRejectReason.CORRUPTED,
                    "expected '" + prefix.trim() + "' but got: " + line);
        }
        try {
            return Integer.parseInt(line.substring(prefix.length()).trim());
        } catch (NumberFormatException e) {
            throw new SnapshotException(SnapshotRejectReason.INCOMPATIBLE_FIELD,
                    "field '" + prefix.trim() + "' not a number: " + line);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String sha256(List<String> lines) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                md.update(line.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
