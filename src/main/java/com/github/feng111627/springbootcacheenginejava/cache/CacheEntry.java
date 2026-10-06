package com.github.feng111627.springbootcacheenginejava.cache;

import java.util.Objects;

/**
 * One cached value together with its absolute expiry instant and the
 * bookkeeping fields used by the eviction policy.
 *
 * <p>An entry becomes expired at {@code nowMillis >= expireAtMillis}. The last
 * access time tracks reads (for LRU) but never extends the lifetime: only an
 * explicit re-put changes {@code expireAtMillis}.
 */
public final class CacheEntry<V> {

    private final V value;
    private final long expireAtMillis;
    private volatile long lastAccessAtMillis;
    private final long sequence;

    CacheEntry(V value, long expireAtMillis, long sequence, long nowMillis) {
        this.value = Objects.requireNonNull(value, "cached value must not be null");
        this.expireAtMillis = expireAtMillis;
        this.lastAccessAtMillis = nowMillis;
        this.sequence = sequence;
    }

    public V value() {
        return value;
    }

    public long expireAtMillis() {
        return expireAtMillis;
    }

    public long lastAccessAtMillis() {
        return lastAccessAtMillis;
    }

    /** Monotonic tie-breaker for recency ordering when access times are equal. */
    public long sequence() {
        return sequence;
    }

    boolean isExpired(long nowMillis) {
        return nowMillis >= expireAtMillis;
    }

    void touch(long nowMillis) {
        this.lastAccessAtMillis = nowMillis;
    }
}
