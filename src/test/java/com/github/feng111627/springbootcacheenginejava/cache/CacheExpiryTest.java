package com.github.feng111627.springbootcacheenginejava.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 业务场景：带过期时间的写入与读取，过期数据不可再读。 */
class CacheExpiryTest {

    private static final Logger log = LoggerFactory.getLogger(CacheExpiryTest.class);

    @Test
    @DisplayName("过期：TTL 内可读，到期后读不到")
    void expiresAfterTtl() {
        log.info("[场景=过期] 用例= TTL 内可读，到期后读不到 - 开始");
        MutableClock clock = new MutableClock(1_000_000L);
        LocalCache<String, String> cache = new LocalCache<>(10, clock);

        cache.put("k1", "v1", 5_000);
        assertEquals(Optional.of("v1"), cache.get("k1"), "TTL 内应命中");

        clock.advance(4_999);
        assertEquals(Optional.of("v1"), cache.get("k1"), "未到期应命中");

        clock.advance(1);
        assertEquals(Optional.empty(), cache.get("k1"), "到期后必须视为未命中");
        assertEquals(0, cache.size(), "过期条目应被移除");
        log.info("[场景=过期] 用例= TTL 内可读，到期后读不到 - 通过");
    }

    @Test
    @DisplayName("过期：永不过期的写入不受时间推进影响")
    void neverExpiresWhenTtlNonPositive() {
        log.info("[场景=过期] 用例= 永不过期写入 - 开始");
        MutableClock clock = new MutableClock(0L);
        LocalCache<String, String> cache = new LocalCache<>(10, clock);

        cache.put("forever", "v", 0);
        clock.advance(365L * 24 * 3600 * 1000);
        assertEquals(Optional.of("v"), cache.get("forever"), "ttl<=0 应永不过期");
        log.info("[场景=过期] 用例= 永不过期写入 - 通过");
    }

    @Test
    @DisplayName("过期：覆盖写入可延长过期时间")
    void overwriteRefreshesExpiry() {
        log.info("[场景=过期] 用例= 覆盖写入刷新过期时间 - 开始");
        MutableClock clock = new MutableClock(0L);
        LocalCache<String, String> cache = new LocalCache<>(10, clock);

        cache.put("k", "old", 1_000);
        clock.advance(900);
        cache.put("k", "new", 1_000);
        clock.advance(900);
        assertEquals(Optional.of("new"), cache.get("k"), "覆盖写后应按新 TTL 计算");
        log.info("[场景=过期] 用例= 覆盖写入刷新过期时间 - 通过");
    }
}
