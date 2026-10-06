package com.github.feng111627.springbootcacheenginejava.cache;

/** Immutable point-in-time view of cache counters. */
public record CacheStats(
        long hits,
        long misses,
        long evictions,
        long expirations,
        int size,
        int capacity
) {
}
