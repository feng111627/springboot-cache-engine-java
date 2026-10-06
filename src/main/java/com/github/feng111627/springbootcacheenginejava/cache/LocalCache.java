package com.github.feng111627.springbootcacheenginejava.cache;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内本地缓存。
 *
 * <ul>
 *   <li>过期：写入时携带 TTL，读取时惰性判断，过期即视为未命中并移除；</li>
 *   <li>淘汰：容量上限，超出时按 LRU（最近最少使用）淘汰，容量恒定不膨胀；</li>
 *   <li>并发：读写互斥保证不脏读、不丢更新；同一 key 未命中时单飞加载，只真正回源一次；</li>
 *   <li>失败：加载失败不写入缓存，异常封装为 {@link CacheLoadException} 并区分原因。</li>
 * </ul>
 */
public class LocalCache<K, V> {

    private final int maxCapacity;
    private final Clock clock;
    /** access-order LinkedHashMap，由 lock 保护，提供 LRU 语义 */
    private final LinkedHashMap<K, CacheEntry<V>> store = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock lock = new ReentrantLock();
    /** 进行中的回源加载，key -> 单飞 future */
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public LocalCache(int maxCapacity) {
        this(maxCapacity, Clock.systemUTC());
    }

    public LocalCache(int maxCapacity, Clock clock) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be positive");
        }
        this.maxCapacity = maxCapacity;
        this.clock = clock;
    }

    /** 写入，ttlMillis <= 0 表示永不过期。 */
    public void put(K key, V value, long ttlMillis) {
        long expireAt = ttlMillis > 0 ? clock.millis() + ttlMillis : 0L;
        lock.lock();
        try {
            store.put(key, new CacheEntry<>(value, expireAt));
            evictOverflow();
        } finally {
            lock.unlock();
        }
    }

    /** 读取；过期视为未命中。 */
    public Optional<V> get(K key) {
        lock.lock();
        try {
            CacheEntry<V> entry = store.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.isExpired(clock.millis())) {
                store.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry.value());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 未命中时回源加载并回填。同一 key 并发未命中只真正加载一次。
     * 加载失败不缓存，抛出 {@link CacheLoadException}。
     */
    public V getOrLoad(K key, long ttlMillis, CacheLoader<K, V> loader) {
        Optional<V> hit = get(key);
        if (hit.isPresent()) {
            return hit.get();
        }
        CompletableFuture<V> future = inFlight.computeIfAbsent(key,
                k -> CompletableFuture.supplyAsync(() -> loadUnchecked(k, loader)));
        try {
            V value = future.get();
            // 只有真正完成加载的这次调用负责回填；其他等待者拿到值后也要保证可见，
            // 统一由所有成功返回者幂等回填（同 key 同值，不会丢更新）。
            put(key, value, ttlMillis);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CacheLoadException(LoadFailureReason.UNKNOWN,
                    "interrupted while loading key " + key, e);
        } catch (ExecutionException e) {
            throw toLoadException(key, e.getCause());
        } finally {
            inFlight.remove(key, future);
        }
    }

    private V loadUnchecked(K key, CacheLoader<K, V> loader) {
        try {
            V value = loader.load(key);
            if (value == null) {
                throw new CacheLoadException(LoadFailureReason.REJECTED,
                        "loader returned null for key " + key, null);
            }
            return value;
        } catch (CacheLoadException e) {
            throw e;
        } catch (Exception e) {
            throw toLoadException(key, e);
        }
    }

    /** 底层异常分类封装，不向调用方原样透出。 */
    private CacheLoadException toLoadException(K key, Throwable cause) {
        if (cause instanceof CacheLoadException cle) {
            return cle;
        }
        LoadFailureReason reason;
        if (cause instanceof TimeoutException) {
            reason = LoadFailureReason.TIMEOUT;
        } else if (cause instanceof IOException) {
            reason = LoadFailureReason.SOURCE_UNAVAILABLE;
        } else if (cause instanceof IllegalArgumentException || cause instanceof SecurityException) {
            reason = LoadFailureReason.REJECTED;
        } else {
            reason = LoadFailureReason.UNKNOWN;
        }
        return new CacheLoadException(reason,
                "failed to load key " + key + ", reason=" + reason, cause);
    }

    private void evictOverflow() {
        var it = store.entrySet().iterator();
        while (store.size() > maxCapacity && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public int size() {
        lock.lock();
        try {
            return store.size();
        } finally {
            lock.unlock();
        }
    }

    /** 导出未过期条目用于落盘。 */
    public Map<K, CacheEntry<V>> snapshotEntries() {
        lock.lock();
        try {
            long now = clock.millis();
            Map<K, CacheEntry<V>> copy = new LinkedHashMap<>();
            store.forEach((k, e) -> {
                if (!e.isExpired(now)) {
                    copy.put(k, e);
                }
            });
            return copy;
        } finally {
            lock.unlock();
        }
    }

    /** 从快照恢复条目；已过期的条目不恢复。 */
    public void restoreEntries(Map<K, CacheEntry<V>> entries) {
        lock.lock();
        try {
            long now = clock.millis();
            entries.forEach((k, e) -> {
                if (!e.isExpired(now)) {
                    store.put(k, e);
                }
            });
            evictOverflow();
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            store.clear();
        } finally {
            lock.unlock();
        }
    }
}
