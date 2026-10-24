package io.github.kaulinmindiola.warden.poc;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** A Bucket4j time source that tests advance explicitly, starting at the current wall-clock time. */
final class MutableTimeMeter implements TimeMeter {

    private final AtomicLong nanos = new AtomicLong(System.currentTimeMillis() * 1_000_000L);

    @Override
    public long currentTimeNanos() {
        return nanos.get();
    }

    /** Declared without {@code @Override} because not every Bucket4j 8.x version defines it. */
    public boolean isWallClockBased() {
        return true;
    }

    void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
