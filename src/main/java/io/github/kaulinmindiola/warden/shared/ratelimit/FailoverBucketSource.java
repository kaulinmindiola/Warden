package io.github.kaulinmindiola.warden.shared.ratelimit;

import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Buckets in Redis while it answers, and instance-local buckets while it does not (REQ-REL-008,
 * SDD V17, ADR-014). The limits are never lifted and never turned into errors.
 *
 * <ul>
 *   <li>The first Redis error (a command timeout of 200 ms or a connection failure) answers the
 *       request from a local bucket and puts the instance in degraded mode.
 *   <li>While degraded, requests use local buckets without waiting for Redis. Once every {@link
 *       #RETRY_INTERVAL}, a single request tries Redis again; if it answers, the instance leaves
 *       degraded mode.
 *   <li>Counts are not copied between stores. A subject keeps what it spent locally during the
 *       outage and finds its Redis bucket as the outage left it, so in the worst case it gets one
 *       extra window's budget when Redis returns.
 * </ul>
 *
 * <p>The gauge {@code warden_ratelimit_degraded} is 1 while this instance uses local buckets. The
 * retry interval follows a monotonic clock, not the application clock, so tests that move the
 * application clock do not change when Redis is retried.
 */
final class FailoverBucketSource implements BucketSource {

    /** How long a degraded instance waits before trying Redis again. */
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

    static final String DEGRADED_METRIC = "warden.ratelimit.degraded";

    private static final Logger log = LoggerFactory.getLogger(FailoverBucketSource.class);

    private final BucketSource shared;
    private final BucketSource local;
    private final LongSupplier nanoTime;
    private final AtomicBoolean degraded = new AtomicBoolean();
    private final AtomicLong retryAtNanos = new AtomicLong();

    /**
     * @param shared the Redis buckets
     * @param local the instance-local buckets
     * @param nanoTime a monotonic clock in nanoseconds, {@code System::nanoTime} outside tests
     */
    FailoverBucketSource(BucketSource shared, BucketSource local, LongSupplier nanoTime, MeterRegistry meterRegistry) {
        this.shared = Objects.requireNonNull(shared, "shared");
        this.local = Objects.requireNonNull(local, "local");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        Gauge.builder(DEGRADED_METRIC, this, source -> source.isDegraded() ? 1.0 : 0.0)
                .description("1 while rate limits use instance-local buckets because Redis failed")
                .register(meterRegistry);
    }

    /**
     * The bucket of the store in use right now. Failures of a Redis bucket surface only when it is
     * used, so {@link BucketRateLimiter} goes through {@link #apply}, which falls back.
     */
    @Override
    public Bucket bucket(String key, RateLimitProperties.Limit limit) {
        return degraded.get() ? local.bucket(key, limit) : shared.bucket(key, limit);
    }

    @Override
    public <T> T apply(String key, RateLimitProperties.Limit limit, Function<Bucket, T> operation) {
        if (degraded.get() && !claimRetry()) {
            return operation.apply(local.bucket(key, limit));
        }
        T result;
        try {
            result = operation.apply(shared.bucket(key, limit));
        } catch (RuntimeException unavailable) {
            enterDegraded(unavailable);
            return operation.apply(local.bucket(key, limit));
        }
        leaveDegraded();
        return result;
    }

    /** Whether this instance enforces the limits with local buckets because Redis failed. */
    boolean isDegraded() {
        return degraded.get();
    }

    /** Lets the next request try Redis at once instead of waiting for the retry interval. */
    void retryNow() {
        retryAtNanos.set(nanoTime.getAsLong());
    }

    /** Whether this caller is the one that tries Redis now; the others keep using local buckets. */
    private boolean claimRetry() {
        long due = retryAtNanos.get();
        long now = nanoTime.getAsLong();
        return now - due >= 0 && retryAtNanos.compareAndSet(due, now + RETRY_INTERVAL.toNanos());
    }

    private void enterDegraded(RuntimeException cause) {
        retryAtNanos.set(nanoTime.getAsLong() + RETRY_INTERVAL.toNanos());
        if (degraded.compareAndSet(false, true)) {
            log.warn(
                    "Redis failed ({}); rate limits use instance-local buckets until it recovers",
                    cause.getClass().getSimpleName());
        }
    }

    private void leaveDegraded() {
        if (degraded.get() && degraded.compareAndSet(true, false)) {
            log.info("Redis is reachable again; rate limits use the shared buckets");
        }
    }
}
