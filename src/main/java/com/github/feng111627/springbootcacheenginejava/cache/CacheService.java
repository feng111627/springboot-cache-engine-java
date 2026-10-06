package com.github.feng111627.springbootcacheenginejava.cache;

import com.github.feng111627.springbootcacheenginejava.cache.snapshot.SnapshotException;
import com.github.feng111627.springbootcacheenginejava.cache.snapshot.SnapshotStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/**
 * 缓存服务：组合本地缓存与快照持久化。
 * 启动时从快照恢复（快照不可用时按原因记录日志，缓存从空开始），停机时落盘。
 */
@Component
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final LocalCache<String, String> cache;
    private final SnapshotStore snapshotStore;

    public CacheService(
            @Value("${cache.max-capacity:1000}") int maxCapacity,
            @Value("${cache.snapshot-file:./cache-snapshot.txt}") String snapshotFile) {
        this.cache = new LocalCache<>(maxCapacity);
        this.snapshotStore = new SnapshotStore(Path.of(snapshotFile));
    }

    @PostConstruct
    void restore() {
        try {
            Map<String, CacheEntry<String>> entries = snapshotStore.load();
            cache.restoreEntries(entries);
            log.info("cache restored from snapshot, {} entries", entries.size());
        } catch (SnapshotException e) {
            log.warn("snapshot not restored, reason={}, detail={}", e.getReason(), e.getMessage());
        }
    }

    @PreDestroy
    void persist() {
        Map<String, CacheEntry<String>> entries = cache.snapshotEntries();
        snapshotStore.save(entries);
        log.info("cache snapshot saved, {} entries", entries.size());
    }

    public LocalCache<String, String> cache() {
        return cache;
    }
}
