package com.github.feng111627.springbootcacheenginejava.cache;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps an {@link ExpiringCache} with load-on-miss/back-fill semantics.
 *
 * <h2>Single-flight guarantee</h2>
 * Concurrent misses for the same key are collapsed: exactly one thread invokes
 * the {@link CacheLoader}; all other threads block on the same result. A new
 * load starts only once the previous attempt has finished and been removed from
 * the in-flight table.
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li>A loader returning {@code null} surfaces as {@link CacheLoadException}
 *       with reason {@link LoadFailureReason#NOT_FOUND}.</li>
 *   <li>A loader throwing surfaces as {@link LoadFailureReason#SOURCE_ERROR};
 *       the original throwable is retained as the cause for diagnostics but is
 *       never rethrown in its raw type.</li>
 *   <li>An interrupted loader surfaces as {@link LoadFailureReason#INTERRUPTED}
 *       and the interrupt flag is restored.</li>
 *   <li>Failures are never written into the cache, so the next miss retries the
 *       source.</li>
 * </ul>
 */
public final class LoadingCache<K, V> {

    private final ExpiringCache<K, V> delegate;
    private final CacheLoader<K, V> loader;
    private final long defaultTtlMillis;
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public LoadingCache(ExpiringCache<K, V> delegate,
                        CacheLoader<K, V> loader,
                        long defaultTtlMillis) {
        if (defaultTtlMillis <= 0) {
            throw new IllegalArgumentException("defaultTtlMillis must be > 0");
        }
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.defaultTtlMillis = defaultTtlMillis;
    }

    /**
     * Returns the cached value or loads it. Always throws
     * {@link CacheLoadException} on failure; raw loader throwables never leak.
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        Optional<V> cached = delegate.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        while (true) {
            CompletableFuture<V> mine = new CompletableFuture<>();
            CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
            if (existing == null) {
                return performLoad(key, mine);
            }
            try {
                return existing.join();
            } catch (CompletionException ce) {
                throw unwrap(ce.getCause(), key);
            }
        }
    }

    /** Explicit back-fill using the same capacity/TTL machinery as loads. */
    public void put(K key, V value, long ttlMillis) {
        delegate.put(key, value, ttlMillis);
    }

    /** Drops a cached value; an already in-flight load is not cancelled. */
    public void invalidate(K key) {
        delegate.remove(key);
    }

    /** Number of live entries in the underlying cache (test/diagnostics). */
    int cachedSize() {
        return delegate.size();
    }

    private V performLoad(K key, CompletableFuture<V> owned) {
        try {
            V loaded = loader.load(key);
            if (loaded == null) {
                CacheLoadException e = new CacheLoadException(
                        LoadFailureReason.NOT_FOUND,
                        "loader returned no value for key: " + key, null);
                owned.completeExceptionally(e);
                throw e;
            }
            delegate.put(key, loaded, defaultTtlMillis);
            owned.complete(loaded);
            return loaded;
        } catch (CacheLoadException alreadyWrapped) {
            owned.completeExceptionally(alreadyWrapped);
            throw alreadyWrapped;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            CacheLoadException e = new CacheLoadException(
                    LoadFailureReason.INTERRUPTED,
                    "load interrupted for key: " + key, ie);
            owned.completeExceptionally(e);
            throw e;
        } catch (Throwable t) {
            CacheLoadException e = new CacheLoadException(
                    LoadFailureReason.SOURCE_ERROR,
                    "source load failed for key: " + key, t);
            owned.completeExceptionally(e);
            throw e;
        } finally {
            // Identity-based removal so a later attempt for the same key survives.
            inFlight.remove(key, owned);
        }
    }

    private static CacheLoadException unwrap(Throwable cause, Object key) {
        if (cause instanceof CacheLoadException cle) {
            return cle;
        }
        return new CacheLoadException(
                LoadFailureReason.SOURCE_ERROR,
                "source load failed for key: " + key, cause);
    }
}
