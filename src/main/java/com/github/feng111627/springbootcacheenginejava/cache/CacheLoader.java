package com.github.feng111627.springbootcacheenginejava.cache;

/**
 * Loads a value from the underlying data source on a cache miss.
 *
 * <p>Implementations signal "the key does not exist downstream" by returning
 * {@code null}; any thrown exception is treated as a source failure.
 */
@FunctionalInterface
public interface CacheLoader<K, V> {
    V load(K key) throws Exception;
}
