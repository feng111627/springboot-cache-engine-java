package com.github.feng111627.springbootcacheenginejava.cache;

/** Wall-clock time source backed by {@link System#currentTimeMillis()}. */
public final class SystemTimeSource implements TimeSource {

    public SystemTimeSource() {
    }

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
