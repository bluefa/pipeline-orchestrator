package com.bff.pipeline.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트용 {@link Clock}: instant를 명시적으로 설정/진행시켜 due-ness와 lease 만료를 결정적으로 제어한다. */
final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    void set(Instant instant) {
        this.now = instant;
    }

    void advance(Duration delta) {
        this.now = this.now.plus(delta);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
