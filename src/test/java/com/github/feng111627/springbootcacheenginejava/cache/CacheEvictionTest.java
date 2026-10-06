package com.github.feng111627.springbootcacheenginejava.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 业务场景：容量上限与 LRU 淘汰，缓存不无限膨胀。 */
class CacheEvictionTest {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictionTest.class);

    @Test
    @DisplayName("淘汰：超出容量时淘汰最久未使用的条目")
    void evictsLeastRecentlyUsed() {
        log.info("[场景=淘汰] 用例= LRU 淘汰最久未使用条目 - 开始");
        LocalCache<String, String> cache = new LocalCache<>(3);
        cache.put("a", "1", 0);
        cache.put("b", "2", 0);
        cache.put("c", "3", 0);

        cache.get("a"); // a 变为最近使用，b 成为最冷
        cache.put("d", "4", 0); // 触发淘汰

        assertEquals(Optional.of("1"), cache.get("a"), "a 刚被访问不应被淘汰");
        assertEquals(Optional.empty(), cache.get("b"), "b 最久未使用应被淘汰");
        assertEquals(Optional.of("3"), cache.get("c"));
        assertEquals(Optional.of("4"), cache.get("d"));
        log.info("[场景=淘汰] 用例= LRU 淘汰最久未使用条目 - 通过");
    }

    @Test
    @DisplayName("淘汰：高压写入下容量恒定不膨胀")
    void capacityNeverExceededUnderPressure() {
        log.info("[场景=淘汰] 用例= 高压写入容量恒定 - 开始");
        int capacity = 100;
        LocalCache<Integer, Integer> cache = new LocalCache<>(capacity);
        for (int i = 0; i < 100_000; i++) {
            cache.put(i, i, 0);
            assertTrue(cache.size() <= capacity, "容量不得超过上限");
        }
        assertEquals(capacity, cache.size(), "最终应恰好保留容量上限个条目");
        assertEquals(Optional.of(99_999), cache.get(99_999), "最新写入应保留");
        assertEquals(Optional.empty(), cache.get(0), "最旧写入应被淘汰");
        log.info("[场景=淘汰] 用例= 高压写入容量恒定 - 通过");
    }

    @Test
    @DisplayName("淘汰：并发读写下容量不膨胀且无异常")
    void concurrentReadWriteKeepsCapacity() throws InterruptedException {
        log.info("[场景=淘汰] 用例= 并发读写容量恒定 - 开始");
        int capacity = 50;
        LocalCache<Integer, Integer> cache = new LocalCache<>(capacity);
        Thread[] threads = new Thread[16];
        for (int t = 0; t < threads.length; t++) {
            int base = t * 10_000;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    cache.put(base + i, i, 0);
                    cache.get(base + i - 1);
                }
            });
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        assertTrue(cache.size() <= capacity, "并发下容量不得超过上限");
        log.info("[场景=淘汰] 用例= 并发读写容量恒定 - 通过, finalSize={}", cache.size());
    }
}
