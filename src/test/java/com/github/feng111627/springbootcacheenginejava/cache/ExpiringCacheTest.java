package com.github.feng111627.springbootcacheenginejava.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("过期与容量规则 (Expiry & Capacity)")
class ExpiringCacheTest {

    private MutableTimeSource clock() {
        return new MutableTimeSource(1_000);
    }

    private ExpiringCache<String, String> cache(int capacity, MutableTimeSource clock) {
        return new ExpiringCache<>(capacity, EvictionPolicy.LRU, clock);
    }

    @Nested
    @DisplayName("TTL 过期")
    class Expiry {
        @Test
        @DisplayName("未过期可读，刚好过期不可读")
        void expiresAfterTtl() {
            MutableTimeSource clock = clock();
            var cache = cache(4, clock);
            cache.put("k", "v", 100);

            clock.advance(99);
            assertEquals(Optional.of("v"), cache.get("k"), "TTL 边界之前必须可读");

            clock.advance(1);
            assertTrue(cache.get("k").isEmpty(), "到达过期时刻后必须视为未命中");
            assertEquals(0, cache.size(), "过期条目被惰性清理，不应继续占位");
            assertEquals(1, cache.stats().expirations());
        }

        @Test
        @DisplayName("读取会刷新访问时间但不会延长存活时间")
        void touchDoesNotExtendTtl() {
            MutableTimeSource clock = clock();
            var cache = cache(4, clock);
            cache.put("k", "v", 100);

            clock.advance(50);
            assertEquals(Optional.of("v"), cache.get("k"));
            clock.advance(49);
            assertEquals(Optional.of("v"), cache.get("k"), "存活期内反复读都应命中");
            clock.advance(1);
            assertTrue(cache.get("k").isEmpty(), "读不能延长 TTL，原始过期时刻仍生效");
        }

        @Test
        @DisplayName("重新写入会以新 TTL 续期")
        void rePutExtendsLifetime() {
            MutableTimeSource clock = clock();
            var cache = cache(4, clock);
            cache.put("k", "v1", 100);
            clock.advance(80);
            cache.put("k", "v2", 100);
            clock.advance(90);
            assertEquals(Optional.of("v2"), cache.get("k"), "重写后续期，旧过期时刻作废");
        }
    }

    @Nested
    @DisplayName("LRU 容量淘汰")
    class Eviction {
        @Test
        @DisplayName("超出容量时按最久未访问淘汰")
        void evictsLeastRecentlyUsed() {
            MutableTimeSource clock = clock();
            var cache = cache(2, clock);
            cache.put("a", "1", 1000);
            cache.put("b", "2", 1000);
            clock.advance(1);
            cache.get("a"); // a 变热，b 最久未访问
            clock.advance(1);
            cache.put("c", "3", 1000);

            assertTrue(cache.get("b").isEmpty(), "b 应作为 LRU 被淘汰");
            assertEquals(Optional.of("1"), cache.get("a"));
            assertEquals(Optional.of("3"), cache.get("c"));
            assertEquals(1, cache.stats().evictions());
        }

        @Test
        @DisplayName("被读热的 key 不会先被淘汰")
        void readRefreshesRecency() {
            MutableTimeSource clock = clock();
            var cache = cache(3, clock);
            cache.put("a", "1", 1000);
            cache.put("b", "2", 1000);
            cache.put("c", "3", 1000);
            clock.advance(10);
            cache.get("a");
            cache.get("c");
            clock.advance(10);
            cache.put("d", "4", 1000);
            assertTrue(cache.get("b").isEmpty(), "从未被再访问的 b 最先出局");
            assertEquals(Optional.of("4"), cache.get("d"));
        }

        @Test
        @DisplayName("容量永不超过上限，连续写入持续触发淘汰")
        void neverExceedsCapacity() {
            MutableTimeSource clock = clock();
            var cache = cache(10, clock);
            for (int i = 0; i < 500; i++) {
                cache.put("k" + i, "v" + i, 10_000);
                assertTrue(cache.size() <= 10,
                        "每次写入后都必须满足 size <= capacity，实际 " + cache.size());
            }
            assertEquals(10, cache.size(), "稳态下应正好占满容量");
            assertEquals(490, cache.stats().evictions());
        }

        @Test
        @DisplayName("写入已存在的 key 不增加容量")
        void overwriteDoesNotGrow() {
            var cache = cache(2, clock());
            cache.put("a", "1", 1000);
            cache.put("a", "2", 1000);
            cache.put("a", "3", 1000);
            assertEquals(1, cache.size());
            assertEquals(0, cache.stats().evictions(), "覆盖写不应触发淘汰");
        }
    }

    @Nested
    @DisplayName("并发一致性")
    class Concurrency {
        @Test
        @DisplayName("高并发写不丢更新、不脏读、不超容")
        void concurrentPuts() throws Exception {
            int writers = 8;
            int perWriter = 2_000;
            // 容量 == key 总数：不触发淘汰，最终值必须等于各线程最后一次写入
            var cache = new ExpiringCache<Integer, Long>(writers, EvictionPolicy.LRU,
                    new SystemTimeSource());
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < writers; t++) {
                int key = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (long s = 1; s <= perWriter; s++) {
                        final long seq = s;
                        cache.put(key, seq, 60_000);
                        // 随时读回：要么没有，要么是自己写过的某个值，绝不脏读
                        var got = cache.get(key);
                        got.ifPresent(v -> assertTrue(v >= 1 && v <= seq,
                                "读到未知/脏数据: " + v));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            for (int t = 0; t < writers; t++) {
                assertEquals((long) perWriter, cache.get(t).orElseThrow(),
                        "key " + t + " 末值丢失，存在丢更新");
            }

            // 高压超容场景：容量在任何时刻都不得被突破
            var bounded = new ExpiringCache<String, String>(8, EvictionPolicy.LRU,
                    new SystemTimeSource());
            ExecutorService hammer = Executors.newFixedThreadPool(8);
            AtomicBoolean stop = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(8);
            for (int t = 0; t < 8; t++) {
                int base = t;
                hammer.submit(() -> {
                    try {
                        for (int i = 0; i < perWriter && !stop.get(); i++) {
                            bounded.put("k-" + ((i + base) % 64), "v", 60_000);
                            assertTrue(bounded.size() <= 8, "容量上限被突破: " + bounded.size());
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            stop.set(true);
            hammer.shutdown();
            assertTrue(bounded.size() <= 8);
        }

        @Test
        @DisplayName("并发读写过期 key 不会重复计数且不会返回旧值")
        void concurrentExpiryRead() throws Exception {
            MutableTimeSource clock = clock();
            var cache = cache(50, clock);
            for (int i = 0; i < 50; i++) {
                cache.put("k" + i, "v", 100);
            }
            clock.advance(100);
            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        if (cache.get("k" + i).isPresent()) {
                            return false; // 过期数据被读到 = 脏读
                        }
                    }
                    return true;
                }));
            }
            start.countDown();
            for (Future<Boolean> f : results) {
                assertTrue(f.get(10, TimeUnit.SECONDS));
            }
            pool.shutdown();
            assertEquals(0, cache.size());
            assertEquals(50, cache.stats().expirations(), "过期清理必须恰好计数一次");
            assertFalse(cache.get("k0").isPresent());
        }
    }
}
