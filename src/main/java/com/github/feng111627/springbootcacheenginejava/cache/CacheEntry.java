package com.github.feng111627.springbootcacheenginejava.cache;

/**
 * 缓存条目：值 + 过期时间戳（毫秒，0 表示永不过期）。
 */
public record CacheEntry<V>(V value, long expireAtMillis) {

    public boolean isExpired(long nowMillis) {
        return expireAtMillis > 0 && nowMillis >= expireAtMillis;
    }
}
