package com.github.feng111627.springbootcacheenginejava.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("并发回源加载 (Loading / Single-Flight)")
class LoadingCacheTest {

    private LoadingCache<String, String> loading(
            int capacity, CacheLoader<String, String> loader, long ttl) {
        return new LoadingCache<>(
                new ExpiringCache<>(capacity, EvictionPolicy.LRU, new SystemTimeSource()),
                loader, ttl);
    }

    @Nested
    @DisplayName("single-flight 语义")
    class SingleFlight {
        @Test
        @DisplayName("同 key 并发未命中只回源一次，所有线程拿到同一结果")
        void concurrentMissLoadsOnce() throws Exception {
            int threads = 32;
            AtomicInteger loads = new AtomicInteger();
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            String loaded = "payload-" + UUID.randomUUID();

            LoadingCache<String, String> cache = loading(16, key -> {
                loads.incrementAndGet();
                inside.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS), "加载线程未被正常放行");
                return loaded;
            }, 60_000);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.get("hot");
                }));
            }
            start.countDown();
            assertTrue(inside.await(5, TimeUnit.SECONDS), "应有一个线程进入回源");
            // 给其它线程足够时间全部阻塞在同一 in-flight 上
            Thread.sleep(100);
            assertEquals(1, loads.get(), "等待期间不得出现第二次回源");
            release.countDown();

            for (Future<String> f : futures) {
                assertSame(loaded, f.get(5, TimeUnit.SECONDS), "所有线程必须拿到同一次加载结果");
            }
            pool.shutdown();
            assertEquals(1, loads.get(), "同 key 高并发未命中只能真正加载一次");
        }

        @Test
        @DisplayName("不同 key 的加载互不阻塞、各自回源")
        void differentKeysLoadInParallel() throws Exception {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            LoadingCache<String, String> cache = loading(16, key -> {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                assertTrue(release.await(5, TimeUnit.SECONDS));
                active.decrementAndGet();
                return "v-" + key;
            }, 60_000);

            ExecutorService pool = Executors.newFixedThreadPool(3);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (String k : new String[]{"x", "y", "z"}) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.get(k);
                }));
            }
            start.countDown();
            Thread.sleep(200);
            release.countDown();
            for (var f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertEquals(3, maxActive.get(), "不同 key 应当并行回源，而不是串行");
        }

        @Test
        @DisplayName("加载成功后回填，后续请求命中缓存不再回源")
        void backFillsAfterLoad() {
            AtomicInteger loads = new AtomicInteger();
            LoadingCache<String, String> cache = loading(4,
                    k -> "v" + loads.incrementAndGet(), 60_000);
            assertEquals("v1", cache.get("k"));
            assertEquals("v1", cache.get("k"));
            assertEquals("v1", cache.get("k"));
            assertEquals(1, loads.get(), "回填后不应重复回源");
        }
    }

    @Nested
    @DisplayName("失败处理")
    class Failures {
        @Test
        @DisplayName("加载抛错不留坏数据，重试会重新回源")
        void failureIsNotCached() {
            AtomicInteger loads = new AtomicInteger();
            LoadingCache<String, String> cache = loading(4, k -> {
                if (loads.incrementAndGet() == 1) {
                    throw new IllegalStateException("downstream boom");
                }
                return "recovered";
            }, 60_000);

            CacheLoadException first = assertThrows(CacheLoadException.class, () -> cache.get("k"));
            assertEquals(LoadFailureReason.SOURCE_ERROR, first.reason());

            // 失败不回填：下一次请求必须重新回源（本次回源恢复成功）
            assertEquals("recovered", cache.get("k"));
            // 之后命中缓存，不再回源
            assertEquals("recovered", cache.get("k"));
            assertEquals(2, loads.get(), "第一次失败、第二次成功回填，之后必须命中缓存");
            assertEquals(1, cache.cachedSize());
        }

        @Test
        @DisplayName("同 key 并发等待者都会收到失败，且失败不回填")
        void concurrentWaitersSeeFailure() throws Exception {
            int threads = 8;
            AtomicInteger loads = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            LoadingCache<String, String> cache = loading(4, k -> {
                loads.incrementAndGet();
                release.await(5, TimeUnit.SECONDS);
                throw new IllegalStateException("source unavailable");
            }, 60_000);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.get("k");
                }));
            }
            start.countDown();
            Thread.sleep(100);
            release.countDown();
            for (Future<String> f : futures) {
                var ex = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> f.get(5, TimeUnit.SECONDS));
                assertTrue(ex.getCause() instanceof CacheLoadException,
                        "等待者必须收到包装后的 CacheLoadException");
                assertEquals(LoadFailureReason.SOURCE_ERROR,
                        ((CacheLoadException) ex.getCause()).reason());
            }
            pool.shutdown();
            assertEquals(1, loads.get(), "失败同样是 single-flight，只回源一次");
            assertEquals(0, cache.cachedSize(), "失败不得在缓存中留下坏数据");
        }

        @Test
        @DisplayName("null 结果归类为 NOT_FOUND，异常归类为 SOURCE_ERROR，可区分")
        void failureReasonsAreDistinguishable() {
            LoadingCache<String, String> notFound = loading(4, k -> null, 60_000);
            CacheLoadException nf = assertThrows(CacheLoadException.class, () -> notFound.get("x"));
            assertEquals(LoadFailureReason.NOT_FOUND, nf.reason());

            LoadingCache<String, String> errored = loading(4,
                    k -> { throw new IllegalArgumentException("bad arg"); }, 60_000);
            CacheLoadException se = assertThrows(CacheLoadException.class, () -> errored.get("x"));
            assertEquals(LoadFailureReason.SOURCE_ERROR, se.reason());

            assertNotEquals(nf.reason(), se.reason(), "两类失败原因必须可区分");
        }

        @Test
        @DisplayName("底层异常类型不会原样透出，只以 cause 形式保留用于诊断")
        void underlyingExceptionIsNotLeaked() {
            var secret = new java.io.IOException("connection reset");
            LoadingCache<String, String> cache = loading(4, k -> {
                throw secret;
            }, 60_000);

            try {
                cache.get("k");
                throw new AssertionError("应当抛出 CacheLoadException");
            } catch (CacheLoadException e) {
                assertEquals(LoadFailureReason.SOURCE_ERROR, e.reason());
                assertSame(secret, e.getCause(), "原始异常仅作为 cause 保留用于诊断");
                assertTrue(e.getMessage().contains("k"), "对外消息应安全且带 key 上下文");
            } catch (Throwable t) {
                throw new AssertionError("底层异常被原样透出: " + t.getClass(), t);
            }
        }
    }

}
