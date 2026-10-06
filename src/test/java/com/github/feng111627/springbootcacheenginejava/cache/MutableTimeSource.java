package com.github.feng111627.springbootcacheenginejava.cache;

import com.github.feng111627.springbootcacheenginejava.cache.TimeSource;

/** Controllable clock for tests. */
public final class MutableTimeSource implements TimeSource {
    private long now;

    public MutableTimeSource(long start) {
        this.now = start;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void advance(long millis) {
        this.now += millis;
    }
}
