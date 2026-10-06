package com.github.feng111627.springbootcacheenginejava.cache;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process cache with per-entry TTL, a hard capacity bound and an LRU
 * eviction policy.
 *
 * <h2>Concurrency model</h2>
 * <ul>
 *   <li>Reads are lock-free: a volatile access timestamp records recency and a
 *       compare-and-remove ensures an expired entry is counted as expired by at
 *       most one thread.</li>
 *   <li>Writes (put/remove/clear) share a single mutation lock so that, after
 *       every completed put, {@link #size()} is always {@code <= capacity}.
 *       The map itself never grows beyond that bound.</li>
 *   <li>Expired entries are swept lazily (on read and during overflow checks);
 *       an expired entry is never returned, regardless of races.</li>
 * </ul>
 */
public final class ExpiringCache<K, V> {

    private final ConcurrentHashMap<K, CacheEntry<V>> map = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();

    private final int capacity;
    private final EvictionPolicy evictionPolicy;
    private final TimeSource timeSource;
    private final AtomicLong sequenceGen = new AtomicLong();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();

    public ExpiringCache(int capacity, EvictionPolicy evictionPolicy, TimeSource timeSource) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
        this.evictionPolicy = Objects.requireNonNull(evictionPolicy, "evictionPolicy");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    /**
     * Returns the live value for the key. Expired entries behave exactly like
     * misses and are removed as a side effect.
     */
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        CacheEntry<V> entry = map.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        long now = timeSource.nowMillis();
        if (entry.isExpired(now)) {
            // CAS-remove: exactly one thread can drop this exact entry instance.
            if (map.remove(key, entry)) {
                expirations.incrementAndGet();
            }
            misses.incrementAndGet();
            return Optional.empty();
        }
        entry.touch(now);
        hits.incrementAndGet();
        return Optional.of(entry.value());
    }

    /** Stores the value with a time-to-live measured from the current time. */
    public void put(K key, V value, long ttlMillis) {
        putWithAbsoluteExpiry(key, value, timeSource.nowMillis() + requirePositiveTtl(ttlMillis));
    }

    /**
     * Stores a value that expires at an absolute instant. Intended for snapshot
     * restore, where expiry instants must survive across restarts.
     */
    void putWithAbsoluteExpiry(K key, V value, long expireAtMillis) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long now = timeSource.nowMillis();
        if (expireAtMillis <= now) {
            // Already dead: never insert, so expired snapshot data cannot linger.
            return;
        }
        CacheEntry<V> entry = new CacheEntry<>(
                value, expireAtMillis, sequenceGen.incrementAndGet(), now);
        synchronized (mutationLock) {
            map.put(key, entry);
            sweepExpiredAndEnforceCapacity();
        }
    }

    public boolean remove(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (mutationLock) {
            return map.remove(key) != null;
        }
    }

    public void clear() {
        synchronized (mutationLock) {
            map.clear();
        }
    }

    public int size() {
        return map.size();
    }

    public CacheStats stats() {
        return new CacheStats(
                hits.get(),
                misses.get(),
                evictions.get(),
                expirations.get(),
                map.size(),
                capacity);
    }

    /** Live (non-expired) entries including absolute expiry, isolated copy for persistence. */
    Map<K, CacheEntry<V>> liveEntries() {
        long now = timeSource.nowMillis();
        Map<K, CacheEntry<V>> out = new HashMap<>();
        for (Map.Entry<K, CacheEntry<V>> e : map.entrySet()) {
            CacheEntry<V> entry = e.getValue();
            if (!entry.isExpired(now)) {
                out.put(e.getKey(), entry);
            }
        }
        return out;
    }

    /** Live (non-expired) key/value pairs as an isolated snapshot copy. */
    public Map<K, V> entriesSnapshot() {
        long now = timeSource.nowMillis();
        Map<K, V> out = new HashMap<>();
        for (Map.Entry<K, CacheEntry<V>> e : map.entrySet()) {
            CacheEntry<V> entry = e.getValue();
            if (!entry.isExpired(now)) {
                out.put(e.getKey(), entry.value());
            }
        }
        return out;
    }

    private static long requirePositiveTtl(long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0, was " + ttlMillis);
        }
        return ttlMillis;
    }

    /**
     * Must run under {@link #mutationLock}. First removes every expired entry,
     * then, while over capacity, evicts according to the configured policy.
     */
    private void sweepExpiredAndEnforceCapacity() {
        long now = timeSource.nowMillis();
        Iterator<Map.Entry<K, CacheEntry<V>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, CacheEntry<V>> e = it.next();
            if (e.getValue().isExpired(now)) {
                it.remove();
                expirations.incrementAndGet();
            }
        }
        while (map.size() > capacity) {
            K victim = selectVictim();
            if (map.remove(victim) != null) {
                evictions.incrementAndGet();
            }
        }
    }

    private K selectVictim() {
        if (evictionPolicy != EvictionPolicy.LRU) {
            throw new IllegalStateException("unsupported eviction policy: " + evictionPolicy);
        }
        K victim = null;
        long victimAccess = Long.MAX_VALUE;
        long victimSeq = Long.MAX_VALUE;
        for (Map.Entry<K, CacheEntry<V>> e : map.entrySet()) {
            CacheEntry<V> candidate = e.getValue();
            long access = candidate.lastAccessAtMillis();
            long seq = candidate.sequence();
            // Oldest access wins; equal timestamps are broken by insertion order.
            if (access < victimAccess || (access == victimAccess && seq < victimSeq)) {
                victim = e.getKey();
                victimAccess = access;
                victimSeq = seq;
            }
        }
        return victim;
    }
}
