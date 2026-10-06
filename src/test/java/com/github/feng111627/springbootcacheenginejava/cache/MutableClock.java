package com.github.feng111627.springbootcacheenginejava.cache;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** 测试用可推进时钟，保证过期场景确定性。 */
class MutableClock extends Clock {

    private long millis;

    MutableClock(long startMillis) {
        this.millis = startMillis;
    }

    void advance(long deltaMillis) {
        millis += deltaMillis;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
