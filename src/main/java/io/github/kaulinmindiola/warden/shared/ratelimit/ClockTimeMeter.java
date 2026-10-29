package io.github.kaulinmindiola.warden.shared.ratelimit;

import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Bucket4j time source backed by the application {@link Clock}, so bucket refills follow the same
 * time as the rest of Warden and tests drive them with their mutable clock.
 */
final class ClockTimeMeter implements TimeMeter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Clock clock;

    ClockTimeMeter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public long currentTimeNanos() {
        Instant now = clock.instant();
        return Math.addExact(Math.multiplyExact(now.getEpochSecond(), NANOS_PER_SECOND), now.getNano());
    }

    /**
     * The time is wall-clock based, so buckets stored in Redis by different instances agree. Declared
     * without {@code @Override}, like the POC-001 time source, because not every Bucket4j 8.x version
     * declares it.
     */
    public boolean isWallClockBased() {
        return true;
    }
}
