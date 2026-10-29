package io.github.kaulinmindiola.warden.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import io.github.kaulinmindiola.warden.shared.crypto.KeyedHasher;
import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimiter.Decision;
import io.github.kaulinmindiola.warden.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Bucket semantics, keys and metrics of {@link BucketRateLimiter} on local buckets and a test clock. */
class BucketRateLimiterTest {

    private static final int CAPACITY = 3;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String EMAIL = "ana@mail.com";

    private final MutableClock clock = new MutableClock();
    private final LocalBuckets buckets = new LocalBuckets(new ClockTimeMeter(clock));
    private final KeyedHasher hasher = new KeyedHasher(new SecretKeySpec(new byte[32], "HmacSHA256"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BucketRateLimiter limiter =
            new BucketRateLimiter(buckets, RateLimitFixtures.uniform(CAPACITY, WINDOW), hasher, registry);

    @Test
    void consumeAllowsUpToTheCapacityThenRejectsWithTheTimeUntilTheRefill() {
        for (int request = 1; request <= CAPACITY; request++) {
            assertThat(limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL))
                    .as("request %d", request)
                    .isEqualTo(Decision.permit());
        }

        Decision rejected = limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isBetween(WINDOW.minusSeconds(1), WINDOW);
    }

    @Test
    void theRetryAfterShrinksAsTheWindowElapses() {
        exhaust(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);

        clock.advance(Duration.ofMinutes(10));

        assertThat(limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL)
                        .retryAfter())
                .isBetween(Duration.ofMinutes(5).minusSeconds(1), Duration.ofMinutes(5));
    }

    @Test
    void anExhaustedBucketRefillsInFullOnlyAfterTheWindow() {
        exhaust(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);

        clock.advance(WINDOW.minusSeconds(1));
        assertThat(limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL)
                        .allowed())
                .isFalse();

        clock.advance(Duration.ofSeconds(2));
        for (int request = 1; request <= CAPACITY; request++) {
            assertThat(limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL)
                            .allowed())
                    .as("request %d after the window", request)
                    .isTrue();
        }
        assertThat(limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL)
                        .allowed())
                .isFalse();
    }

    @Test
    void checkNeverConsumes() {
        for (int request = 0; request < CAPACITY * 4; request++) {
            assertThat(limiter.check(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL))
                    .isEqualTo(Decision.permit());
        }

        exhaust(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);
    }

    @Test
    void checkRejectsAnExhaustedBucketWithTheTimeUntilTheRefill() {
        exhaust(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);

        Decision checked = limiter.check(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);

        assertThat(checked.allowed()).isFalse();
        assertThat(checked.retryAfter()).isBetween(WINDOW.minusSeconds(1), WINDOW);
    }

    @Test
    void bucketsAreIndependentPerSubjectAndPerPolicy() {
        exhaust(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);

        assertThat(limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, "bob@mail.com")
                        .allowed())
                .isTrue();
        assertThat(limiter.consume(RateLimitPolicy.PASSWORD_RESET_PER_EMAIL, EMAIL)
                        .allowed())
                .isTrue();
    }

    @Test
    void theKeyHoldsThePolicyAndTheHmacOfTheSubjectButNeverTheSubject() {
        limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);
        limiter.consume(RateLimitPolicy.LOGIN_PER_IP, "203.0.113.7");

        assertThat(buckets.requestedKeys())
                .containsExactly(
                        "warden:rl:login-failures-per-email:" + hasher.hmac(EMAIL),
                        "warden:rl:login-per-ip:" + hasher.hmac("203.0.113.7"));
        assertThat(buckets.requestedKeys())
                .noneMatch(key -> key.contains("ana") || key.contains("mail.com") || key.contains("203.0"));
    }

    @Test
    void everyRejectionIsCountedUnderItsPolicyAndPermitsAreNot() {
        for (int request = 0; request < CAPACITY; request++) {
            limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);
        }
        assertThat(rejections(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL)).isZero();

        limiter.check(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);
        limiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, EMAIL);
        limiter.consume(RateLimitPolicy.LOGIN_PER_IP, "203.0.113.7");

        assertThat(rejections(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL)).isEqualTo(2.0);
        assertThat(rejections(RateLimitPolicy.LOGIN_PER_IP)).isZero();
    }

    @Test
    void everyPolicyHasItsRejectionCounterFromTheStart() {
        assertThat(registry.find(BucketRateLimiter.REJECTIONS_METRIC).counters())
                .hasSize(RateLimitPolicy.values().length)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            assertThat(rejections(policy)).isZero();
        }
    }

    @Test
    void aBlankOrMissingSubjectIsRejected() {
        assertThatThrownBy(() -> limiter.consume(RateLimitPolicy.LOGIN_PER_IP, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.check(RateLimitPolicy.LOGIN_PER_IP, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> limiter.consume(null, EMAIL)).isInstanceOf(NullPointerException.class);
    }

    private void exhaust(RateLimitPolicy policy, String subject) {
        for (int request = 0; request < CAPACITY; request++) {
            limiter.consume(policy, subject);
        }
        assertThat(limiter.consume(policy, subject).allowed()).isFalse();
    }

    private double rejections(RateLimitPolicy policy) {
        return registry.get(BucketRateLimiter.REJECTIONS_METRIC)
                .tag(BucketRateLimiter.POLICY_TAG, policy.key())
                .counter()
                .count();
    }

    /** In-memory buckets on the test clock that remember which keys were requested. */
    private static final class LocalBuckets implements BucketSource {

        private final TimeMeter timeMeter;
        private final Map<String, Bucket> buckets = new HashMap<>();
        private final List<String> requestedKeys = new ArrayList<>();

        LocalBuckets(TimeMeter timeMeter) {
            this.timeMeter = timeMeter;
        }

        @Override
        public Bucket bucket(String key, RateLimitProperties.Limit limit) {
            if (!requestedKeys.contains(key)) {
                requestedKeys.add(key);
            }
            return buckets.computeIfAbsent(
                    key,
                    unused -> Bucket.builder()
                            .withCustomTimePrecision(timeMeter)
                            .addLimit(bandwidth -> bandwidth
                                    .capacity(limit.capacity())
                                    .refillIntervally(limit.capacity(), limit.window()))
                            .build());
        }

        List<String> requestedKeys() {
            return requestedKeys;
        }
    }
}
