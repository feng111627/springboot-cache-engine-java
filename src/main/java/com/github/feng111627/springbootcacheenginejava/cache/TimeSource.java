package com.github.feng111627.springbootcacheenginejava.cache;

/** Abstraction over the clock so expiry logic is deterministically testable. */
public interface TimeSource {
    long nowMillis();
}
