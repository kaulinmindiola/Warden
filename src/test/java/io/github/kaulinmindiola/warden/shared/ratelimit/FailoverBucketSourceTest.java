package io.github.kaulinmindiola.warden.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.bucket4j.Bucket;
import io.github.kaulinmindiola.warden.shared.crypto.KeyedHasher;
import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimiter.Decision;
import io.github.kaulinmindiola.warden.support.MutableClock;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Switching between Redis and local buckets (REQ-REL-008). "Redis" is a local source that can be
 * made unreachable: its buckets then fail on every operation, as a Redis bucket does at the
 * command timeout. The retry interval follows a fake monotonic clock.
 */
class FailoverBucketSourceTest {

    private static final RateLimitProperties.Limit LIMIT = new RateLimitProperties.Limit(5, Duration.ofMinutes(15));
    private static final String KEY = "warden:rl:login-failures-per-email:subject";

    private final MutableClock clock = new MutableClock();
    private final AtomicLong nanos = new AtomicLong();
    private final SwitchableRedis redis = new SwitchableRedis(new LocalBucketSource(new ClockTimeMeter(clock)));
    private final LocalBucketSource local = new LocalBucketSource(new ClockTimeMeter(clock));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FailoverBucketSource buckets = new FailoverBucketSource(redis, local, nanos::get, registry);

    @Test
    void whileRedisAnswersTheSharedBucketsAreUsed() {
        assertThat(consume()).isTrue();

        assertThat(redis.calls()).isEqualTo(1);
        assertThat(local.size()).isZero();
        assertThat(buckets.isDegraded()).isFalse();
        assertThat(gauge()).isZero();
    }

    @Test
    void aRedisFailureIsAnsweredFromALocalBucketAndDegradesTheInstance() {
        redis.fail();

        assertThat(consume()).isTrue();

        assertThat(buckets.isDegraded()).isTrue();
        assertThat(gauge()).isEqualTo(1.0);
        assertThat(local.size()).isEqualTo(1);
    }

    @Test
    @Verifies("REQ-REL-008")
    void theLimitsStillHoldWithLocalBuckets() {
        redis.fail();
        RateLimiter limiter = new BucketRateLimiter(
                buckets,
                RateLimitFixtures.uniform(LIMIT.capacity(), LIMIT.window()),
                new KeyedHasher(new SecretKeySpec(new byte[32], "HmacSHA256")),
                new SimpleMeterRegistry());

        for (int request = 1; request <= LIMIT.capacity(); request++) {
            assertThat(limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, "ana@mail.com"))
                    .as("request %d", request)
                    .isEqualTo(Decision.permit());
        }
        Decision rejected = limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, "ana@mail.com");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isBetween(LIMIT.window().minusSeconds(1), LIMIT.window());
    }

    @Test
    void whileDegradedRedisIsTriedAgainOnlyOncePerRetryInterval() {
        redis.fail();
        consume();
        consume();
        consume();
        assertThat(redis.calls()).as("only the call that failed").isEqualTo(1);

        nanos.addAndGet(FailoverBucketSource.RETRY_INTERVAL.toNanos() - 1);
        consume();
        assertThat(redis.calls()).isEqualTo(1);

        nanos.addAndGet(1);
        consume();
        assertThat(redis.calls()).as("the retry").isEqualTo(2);
        assertThat(buckets.isDegraded()).isTrue();

        consume();
        assertThat(redis.calls()).as("the next retry waits a whole interval").isEqualTo(2);
    }

    @Test
    void theInstanceLeavesDegradedModeWhenRedisAnswersTheRetry() {
        redis.fail();
        consume();
        redis.recover();

        nanos.addAndGet(FailoverBucketSource.RETRY_INTERVAL.toNanos());
        consume();

        assertThat(buckets.isDegraded()).isFalse();
        assertThat(gauge()).isZero();
        consume();
        assertThat(redis.calls()).isEqualTo(3);
    }

    @Test
    void retryNowLetsTheNextRequestTryRedisAtOnce() {
        redis.fail();
        consume();
        redis.recover();

        buckets.retryNow();
        consume();

        assertThat(buckets.isDegraded()).isFalse();
    }

    @Test
    void bucketReturnsTheBucketOfTheStoreInUse() {
        Bucket shared = buckets.bucket(KEY, LIMIT);
        assertThat(shared).isSameAs(redis.bucket(KEY, LIMIT));

        redis.fail();
        consume();

        assertThat(buckets.bucket(KEY, LIMIT)).isSameAs(local.bucket(KEY, LIMIT));
    }

    private boolean consume() {
        return buckets.apply(KEY, LIMIT, bucket -> bucket.tryConsume(1));
    }

    private double gauge() {
        return registry.get(FailoverBucketSource.DEGRADED_METRIC).gauge().value();
    }

    /** A bucket source standing in for Redis that can be made unreachable. */
    private static final class SwitchableRedis implements BucketSource {

        private final BucketSource delegate;
        private final Bucket unreachable = mock(Bucket.class, invocation -> {
            throw new RedisCommandTimeoutException("Command timed out");
        });
        private int calls;
        private boolean down;

        SwitchableRedis(BucketSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Bucket bucket(String key, RateLimitProperties.Limit limit) {
            calls++;
            return down ? unreachable : delegate.bucket(key, limit);
        }

        void fail() {
            down = true;
        }

        void recover() {
            down = false;
        }

        int calls() {
            return calls;
        }
    }
}
