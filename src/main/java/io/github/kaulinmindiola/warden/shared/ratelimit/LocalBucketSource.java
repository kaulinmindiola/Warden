package io.github.kaulinmindiola.warden.shared.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Instance-local buckets in a {@link ConcurrentHashMap}, used while Redis is unavailable
 * (REQ-REL-008, SDD V17, ADR-014). They apply the same policies with the same semantics as the
 * Redis buckets: {@code capacity} tokens refilled in full once per window, on the application
 * clock.
 *
 * <p>Each instance counts on its own, so with several instances the effective limit is multiplied
 * by their number (RISK-012, accepted for 1.0).
 *
 * <p>A bucket left unused for a whole window is full again, so dropping it changes nothing. A
 * sweep removes those buckets at most once per {@link #SWEEP_INTERVAL}, which keeps the map to the
 * buckets that still limit something.
 */
final class LocalBucketSource implements BucketSource {

    /** Minimum time between two sweeps of the idle buckets. */
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final TimeMeter timeMeter;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    // ReentrantLock instead of synchronized: a virtual thread blocked in synchronized pins its carrier.
    private final ReentrantLock sweepLock = new ReentrantLock();
    private volatile long nextSweepNanos;

    LocalBucketSource(TimeMeter timeMeter) {
        this.timeMeter = Objects.requireNonNull(timeMeter, "timeMeter");
        this.nextSweepNanos = timeMeter.currentTimeNanos() + SWEEP_INTERVAL.toNanos();
    }

    @Override
    public Bucket bucket(String key, RateLimitProperties.Limit limit) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(limit, "limit");
        long now = timeMeter.currentTimeNanos();
        sweepIfDue(now);
        // The bucket is marked as used inside compute, under the same per-key lock the sweep takes
        // to remove it, so a bucket that is being handed out is never dropped as idle.
        return entries.compute(key, (unused, entry) -> {
                    Entry current = entry != null ? entry : new Entry(newBucket(limit), limit.window());
                    current.lastUsedNanos = now;
                    return current;
                })
                .bucket;
    }

    /** Number of buckets currently held. */
    int size() {
        return entries.size();
    }

    private void sweepIfDue(long now) {
        if (now - nextSweepNanos < 0 || !sweepLock.tryLock()) {
            return;
        }
        try {
            if (now - nextSweepNanos < 0) {
                return;
            }
            nextSweepNanos = now + SWEEP_INTERVAL.toNanos();
            for (String key : entries.keySet()) {
                entries.computeIfPresent(key, (unused, entry) -> entry.isFullAgainAt(now) ? null : entry);
            }
        } finally {
            sweepLock.unlock();
        }
    }

    /** The same bandwidth as {@link RedisBucketSource#configuration}, on the application clock. */
    private Bucket newBucket(RateLimitProperties.Limit limit) {
        return Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(bandwidth ->
                        bandwidth.capacity(limit.capacity()).refillIntervally(limit.capacity(), limit.window()))
                .build();
    }

    private static final class Entry {

        private final Bucket bucket;
        private final long windowNanos;
        private volatile long lastUsedNanos;

        private Entry(Bucket bucket, Duration window) {
            this.bucket = bucket;
            this.windowNanos = window.toNanos();
        }

        /** A full window without use always crosses a refill, which fills the bucket completely. */
        private boolean isFullAgainAt(long now) {
            return now - lastUsedNanos >= windowNanos;
        }
    }
}
