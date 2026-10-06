package com.github.feng111627.springbootcacheenginejava.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 业务场景：未命中回源加载，高并发下同 key 只加载一次，失败不缓存且原因可区分。 */
class CacheConcurrentLoadTest {

    private static final Logger log = LoggerFactory.getLogger(CacheConcurrentLoadTest.class);

    @Test
    @DisplayName("并发加载：同一 key 高并发未命中只真正加载一次")
    void singleFlightUnderConcurrency() throws Exception {
        log.info("[场景=并发加载] 用例= 同 key 并发只加载一次 - 开始");
        LocalCache<String, String> cache = new LocalCache<>(100);
        AtomicInteger loadCount = new AtomicInteger();
        CacheLoader<String, String> loader = key -> {
            loadCount.incrementAndGet();
            Thread.sleep(100); // 放大并发窗口
            return "value-of-" + key;
        };

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return cache.getOrLoad("hot-key", 60_000, loader);
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        for (Future<String> f : futures) {
            assertEquals("value-of-hot-key", f.get(5, TimeUnit.SECONDS), "所有等待者应拿到同一个值");
        }
        pool.shutdown();
        assertEquals(1, loadCount.get(), "同一 key 并发未命中必须只真正加载一次");
        assertEquals(1, cache.size(), "加载结果应回填缓存");
        log.info("[场景=并发加载] 用例= 同 key 并发只加载一次 - 通过, loadCount={}", loadCount.get());
    }

    @Test
    @DisplayName("并发加载：加载失败不缓存坏数据，后续可重试成功")
    void failureNotCachedAndRetryable() {
        log.info("[场景=并发加载] 用例= 失败不缓存可重试 - 开始");
        LocalCache<String, String> cache = new LocalCache<>(10);
        AtomicInteger attempts = new AtomicInteger();
        CacheLoader<String, String> flaky = key -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("downstream down");
            }
            return "recovered";
        };

        CacheLoadException ex = assertThrows(CacheLoadException.class,
                () -> cache.getOrLoad("k", 60_000, flaky));
        assertEquals(LoadFailureReason.SOURCE_UNAVAILABLE, ex.getReason());
        assertTrue(cache.get("k").isEmpty(), "失败结果不得留在缓存里");
        assertEquals(0, cache.size());

        assertEquals("recovered", cache.getOrLoad("k", 60_000, flaky), "重试应成功");
        assertEquals(2, attempts.get());
        log.info("[场景=并发加载] 用例= 失败不缓存可重试 - 通过");
    }

    @Test
    @DisplayName("并发加载：不同底层失败映射为可区分原因，且不透出原始异常")
    void failureReasonsAreDistinct() {
        log.info("[场景=并发加载] 用例= 失败原因区分 - 开始");
        LocalCache<String, String> cache = new LocalCache<>(10);

        CacheLoadException timeout = assertThrows(CacheLoadException.class,
                () -> cache.getOrLoad("t", 1_000, key -> {
                    throw new TimeoutException("too slow");
                }));
        assertEquals(LoadFailureReason.TIMEOUT, timeout.getReason());

        CacheLoadException unavailable = assertThrows(CacheLoadException.class,
                () -> cache.getOrLoad("u", 1_000, key -> {
                    throw new IOException("connection refused");
                }));
        assertEquals(LoadFailureReason.SOURCE_UNAVAILABLE, unavailable.getReason());

        CacheLoadException rejected = assertThrows(CacheLoadException.class,
                () -> cache.getOrLoad("r", 1_000, key -> {
                    throw new IllegalArgumentException("bad key");
                }));
        assertEquals(LoadFailureReason.REJECTED, rejected.getReason());

        CacheLoadException unknown = assertThrows(CacheLoadException.class,
                () -> cache.getOrLoad("x", 1_000, key -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals(LoadFailureReason.UNKNOWN, unknown.getReason());

        assertNotEquals(IOException.class, unavailable.getClass(),
                "底层异常不得原样透出，必须封装为 CacheLoadException");
        log.info("[场景=并发加载] 用例= 失败原因区分 - 通过");
    }

    @Test
    @DisplayName("并发加载：命中后不再回源")
    void hitDoesNotReload() {
        log.info("[场景=并发加载] 用例= 命中不回源 - 开始");
        LocalCache<String, String> cache = new LocalCache<>(10);
        AtomicInteger loadCount = new AtomicInteger();
        CacheLoader<String, String> loader = key -> {
            loadCount.incrementAndGet();
            return "v";
        };
        cache.getOrLoad("k", 60_000, loader);
        cache.getOrLoad("k", 60_000, loader);
        cache.getOrLoad("k", 60_000, loader);
        assertEquals(1, loadCount.get(), "命中后不得重复回源");
        log.info("[场景=并发加载] 用例= 命中不回源 - 通过");
    }
}
