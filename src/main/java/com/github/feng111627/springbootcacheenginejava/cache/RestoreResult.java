package com.github.feng111627.springbootcacheenginejava.cache;

/** Outcome of a snapshot restore attempt. */
public record RestoreResult(
        boolean snapshotFound,
        int entriesRead,
        int entriesRestored,
        int expiredSkipped,
        int evictedOnRestore
) {
}
