package io.github.kaulinmindiola.warden.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.shared.crypto.KeyedHasher;
import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimiter.Decision;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The application {@link RateLimiter} against the Redis 8 test container, with buckets driven by
 * the test clock: exhaustion and recovery, hashed keys with an expiry, the rejection metric and
 * buckets shared by independent instances. The SRS values of the limits are checked against the
 * main {@code application.yaml} by {@code RateLimitPropertiesTest}, because the test classpath
 * raises the per-IP capacities.
 */
class RedisRateLimiterIT extends IntegrationTest {

    private static final RateLimitPolicy POLICY = RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private RateLimitProperties properties;

    @Autowired
    private KeyedHasher keyedHasher;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Test
    void aBucketInRedisIsExhaustedAndRecoversAfterItsWindow() {
        String subject = newEmail();
        RateLimitProperties.Limit limit = properties.limitFor(POLICY);

        consumeAll(rateLimiter, subject, limit.capacity());
        Decision rejected = rateLimiter.consume(POLICY, subject);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isBetween(limit.window().minusSeconds(1), limit.window());

        clock.advance(limit.window().minusSeconds(1));
        assertThat(rateLimiter.consume(POLICY, subject).allowed()).isFalse();

        clock.advance(Duration.ofSeconds(2));
        assertThat(rateLimiter.consume(POLICY, subject).allowed()).isTrue();
    }

    @Test
    void checkReadsTheBucketWithoutConsumingIt() {
        String subject = newEmail();
        int capacity = properties.limitFor(POLICY).capacity();

        for (int request = 0; request < capacity * 3; request++) {
            assertThat(rateLimiter.check(POLICY, subject)).isEqualTo(Decision.permit());
        }
        consumeAll(rateLimiter, subject, capacity);

        assertThat(rateLimiter.check(POLICY, subject).allowed()).isFalse();
    }

    @Test
    void theBucketIsStoredUnderTheHmacOfTheSubjectWithAnExpiry() {
        String subject = newEmail();
        Duration window = properties.limitFor(POLICY).window();

        rateLimiter.consume(POLICY, subject);

        String key = BucketRateLimiter.key(POLICY, keyedHasher.hmac(subject));
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key))
                .isPositive()
                .isLessThanOrEqualTo(
                        window.plus(RedisBucketSource.KEEP_AFTER_REFILL).toSeconds());
        Set<String> keys = redis.keys(BucketRateLimiter.KEY_PREFIX + "*");
        assertThat(keys).isNotEmpty().noneMatch(stored -> stored.contains(subject) || stored.contains("@"));
    }

    @Test
    void aRejectionIncrementsTheMetricOfItsPolicyOnly() {
        String subject = newEmail();
        double before = rejections(POLICY);
        double otherBefore = rejections(RateLimitPolicy.LOGIN_PER_IP);

        consumeAll(rateLimiter, subject, properties.limitFor(POLICY).capacity());
        rateLimiter.consume(POLICY, subject);

        assertThat(rejections(POLICY) - before).isEqualTo(1.0);
        assertThat(rejections(RateLimitPolicy.LOGIN_PER_IP) - otherBefore).isZero();
    }

    @Test
    void everyInstanceSharesTheSameBucketsThroughRedis() {
        String subject = newEmail();
        int capacity = properties.limitFor(POLICY).capacity();

        try (RedisBucketSource otherInstanceBuckets =
                RedisBucketSource.create(redisHost, redisPort, Duration.ofSeconds(2), new ClockTimeMeter(clock))) {
            RateLimiter otherInstance =
                    new BucketRateLimiter(otherInstanceBuckets, properties, keyedHasher, new SimpleMeterRegistry());

            consumeAll(rateLimiter, subject, capacity);

            assertThat(otherInstance.consume(POLICY, subject).allowed()).isFalse();
        }
    }

    private static void consumeAll(RateLimiter limiter, String subject, int requests) {
        for (int request = 1; request <= requests; request++) {
            assertThat(limiter.consume(POLICY, subject).allowed())
                    .as("request %d", request)
                    .isTrue();
        }
    }

    private double rejections(RateLimitPolicy policy) {
        return meterRegistry
                .get(BucketRateLimiter.REJECTIONS_METRIC)
                .tag(BucketRateLimiter.POLICY_TAG, policy.key())
                .counter()
                .count();
    }

    private static String newEmail() {
        return "rate-" + UUID.randomUUID() + "@example.com";
    }
}
