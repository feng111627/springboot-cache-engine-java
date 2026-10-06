package com.github.feng111627.springbootcacheenginejava.cache;

/**
 * 回源加载器。实现方抛出的任何异常都会被封装为 {@link CacheLoadException}。
 */
@FunctionalInterface
public interface CacheLoader<K, V> {
    V load(K key) throws Exception;
}
