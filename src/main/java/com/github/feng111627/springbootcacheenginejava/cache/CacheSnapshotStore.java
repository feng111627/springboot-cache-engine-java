package com.github.feng111627.springbootcacheenginejava.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned JSON persistence for {@link ExpiringCache} contents.
 *
 * <h2>On-disk format</h2>
 * <pre>{@code
 * {
 *   "version": 1,
 *   "writtenAtMillis": 1728... ,
 *   "entries": [
 *     { "key": ..., "value": ..., "expireAtMillis": 1728... }
 *   ]
 * }
 * }</pre>
 * Snapshot time is stored as absolute epoch millis so it is restart-safe.
 *
 * <h2>Compatibility</h2>
 * Formats from {@link #MIN_SUPPORTED_FORMAT_VERSION} through
 * {@link #CURRENT_FORMAT_VERSION} are accepted. A newer version is rejected
 * with {@link SnapshotCorruptReason#UNSUPPORTED_VERSION}; malformed content is
 * rejected with a precise {@link SnapshotCorruptReason} instead of being
 * silently dropped. Keys must be unique; duplicates are rejected so entries can
 * never restore onto the wrong value.
 */
public final class CacheSnapshotStore<K, V> {

    public static final int CURRENT_FORMAT_VERSION = 1;
    /** Oldest snapshot format version this build can still read. */
    public static final int MIN_SUPPORTED_FORMAT_VERSION = 1;

    private static final String FIELD_VERSION = "version";
    private static final String FIELD_WRITTEN_AT = "writtenAtMillis";
    private static final String FIELD_ENTRIES = "entries";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_VALUE = "value";
    private static final String FIELD_EXPIRE_AT = "expireAtMillis";

    private final Path file;
    private final Class<K> keyClass;
    private final Class<V> valueClass;
    private final ExpiringCache<K, V> cache;
    private final TimeSource timeSource;
    private final ObjectMapper mapper;

    public CacheSnapshotStore(Path file,
                              Class<K> keyClass,
                              Class<V> valueClass,
                              ExpiringCache<K, V> cache) {
        this(file, keyClass, valueClass, cache,
                new ObjectMapper(), new SystemTimeSource());
    }

    CacheSnapshotStore(Path file,
                       Class<K> keyClass,
                       Class<V> valueClass,
                       ExpiringCache<K, V> cache,
                       ObjectMapper mapper,
                       TimeSource timeSource) {
        this.file = Objects.requireNonNull(file, "file");
        this.keyClass = Objects.requireNonNull(keyClass, "keyClass");
        this.valueClass = Objects.requireNonNull(valueClass, "valueClass");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    /** Atomically writes the live cache contents to the snapshot file. */
    public void save() {
        ObjectNode root = mapper.createObjectNode();
        root.put(FIELD_VERSION, CURRENT_FORMAT_VERSION);
        root.put(FIELD_WRITTEN_AT, timeSource.nowMillis());
        ArrayNode entries = root.putArray(FIELD_ENTRIES);
        for (Map.Entry<K, CacheEntry<V>> e : cache.liveEntries().entrySet()) {
            CacheEntry<V> entry = e.getValue();
            ObjectNode en = entries.addObject();
            en.set(FIELD_KEY, mapper.valueToTree(e.getKey()));
            en.set(FIELD_VALUE, mapper.valueToTree(entry.value()));
            en.put(FIELD_EXPIRE_AT, entry.expireAtMillis());
        }
        writeAtomically(root);
    }

    /**
     * Restores the snapshot into the cache. If no snapshot file exists nothing
     * is restored and {@link RestoreResult#snapshotFound()} is {@code false}.
     *
     * @throws SnapshotFormatException with a distinguishable reason on any
     *         corrupt or incompatible snapshot; the cache is left unchanged.
     */
    public RestoreResult restore() {
        if (!Files.exists(file)) {
            return new RestoreResult(false, 0, 0, 0, 0);
        }
        JsonNode root = readRoot();
        int version = requireVersion(root);
        if (version > CURRENT_FORMAT_VERSION) {
            throw new SnapshotFormatException(SnapshotCorruptReason.UNSUPPORTED_VERSION,
                    "snapshot version " + version + " is newer than supported "
                            + CURRENT_FORMAT_VERSION + ": " + file);
        }
        JsonNode entriesNode = root.get(FIELD_ENTRIES);
        if (entriesNode == null || !entriesNode.isArray()) {
            throw new SnapshotFormatException(SnapshotCorruptReason.MALFORMED_FIELDS,
                    "missing or non-array field '" + FIELD_ENTRIES + "': " + file);
        }

        int read = 0;
        int expiredSkipped = 0;
        Set<K> seen = new HashSet<>();
        // Stage first; only touch the real cache after the whole snapshot passes
        // validation, so a corrupt file cannot partially poison the cache.
        java.util.List<StagedEntry<K, V>> staged = new java.util.ArrayList<>();
        for (JsonNode item : entriesNode) {
            if (item == null || !item.isObject()) {
                throw new SnapshotFormatException(SnapshotCorruptReason.MALFORMED_FIELDS,
                        "entries must be JSON objects: " + file);
            }
            K key = convertField(item, FIELD_KEY, keyClass);
            V value = convertField(item, FIELD_VALUE, valueClass);
            JsonNode expireNode = item.get(FIELD_EXPIRE_AT);
            if (expireNode == null || !expireNode.canConvertToLong()) {
                throw new SnapshotFormatException(SnapshotCorruptReason.MALFORMED_FIELDS,
                        "missing or non-integer field '" + FIELD_EXPIRE_AT + "': " + file);
            }
            long expireAt = expireNode.asLong();
            if (!seen.add(key)) {
                throw new SnapshotFormatException(SnapshotCorruptReason.DUPLICATE_KEY,
                        "duplicate key in snapshot: " + key);
            }
            read++;
            if (expireAt <= timeSource.nowMillis()) {
                expiredSkipped++;
            } else {
                staged.add(new StagedEntry<>(key, value, expireAt));
            }
        }

        long evictionsBefore = cache.stats().evictions();
        for (StagedEntry<K, V> s : staged) {
            cache.putWithAbsoluteExpiry(s.key(), s.value(), s.expireAt());
        }
        int evicted = (int) (cache.stats().evictions() - evictionsBefore);
        return new RestoreResult(true, read, staged.size(), expiredSkipped, evicted);
    }

    private JsonNode readRoot() {
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            if (root == null || !root.isObject()) {
                throw new SnapshotFormatException(SnapshotCorruptReason.UNREADABLE,
                        "snapshot root must be a JSON object: " + file);
            }
            return root;
        } catch (SnapshotFormatException sfe) {
            throw sfe;
        } catch (IOException e) {
            throw new SnapshotFormatException(SnapshotCorruptReason.UNREADABLE,
                    "snapshot is not readable as JSON: " + file, e);
        }
    }

    private int requireVersion(JsonNode root) {
        JsonNode versionNode = root.get(FIELD_VERSION);
        if (versionNode == null || !versionNode.canConvertToInt()) {
            throw new SnapshotFormatException(SnapshotCorruptReason.MALFORMED_FIELDS,
                    "missing or non-integer field '" + FIELD_VERSION + "': " + file);
        }
        int version = versionNode.asInt();
        if (version < MIN_SUPPORTED_FORMAT_VERSION) {
            throw new SnapshotFormatException(SnapshotCorruptReason.UNSUPPORTED_VERSION,
                    "snapshot version " + version + " is older than supported minimum "
                            + MIN_SUPPORTED_FORMAT_VERSION + ": " + file);
        }
        return version;
    }

    private <T> T convertField(JsonNode item, String field, Class<T> type) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull()) {
            throw new SnapshotFormatException(SnapshotCorruptReason.MALFORMED_FIELDS,
                    "missing field '" + field + "' in snapshot entry: " + file);
        }
        try {
            return mapper.treeToValue(node, type);
        } catch (IOException e) {
            throw new SnapshotFormatException(SnapshotCorruptReason.MALFORMED_FIELDS,
                    "field '" + field + "' is incompatible with " + type.getSimpleName()
                            + ": " + file, e);
        }
    }

    private void writeAtomically(ObjectNode root) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, "cache-snapshot-", ".tmp");
            try {
                Files.write(tmp, mapper.writeValueAsBytes(root));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write snapshot: " + file, e);
        }
    }

    private record StagedEntry<K, V>(K key, V value, long expireAt) {
    }
}
