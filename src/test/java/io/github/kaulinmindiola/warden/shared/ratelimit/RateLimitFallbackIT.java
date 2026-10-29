package io.github.kaulinmindiola.warden.shared.ratelimit;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.shared.crypto.KeyedHasher;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/**
 * REQ-REL-008 with Redis really unreachable (SDD V17, ADR-014): the shared Redis container is
 * frozen, so every rate-limit command fails at its 200 ms timeout and the limits are enforced with
 * instance-local buckets. Before and after every test the limiter is back on the Redis buckets, so
 * the other tests of the shared context are unaffected.
 */
class RateLimitFallbackIT extends IntegrationTest {

    private static final String LOGIN = API + "/auth/login";
    private static final String WRONG_PASSWORD = "not the right password";
    private static final RateLimitPolicy POLICY = RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private FailoverBucketSource buckets;

    @Autowired
    private RateLimitProperties properties;

    @Autowired
    private KeyedHasher keyedHasher;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meterRegistry;

    private IamTestClient iam;
    private boolean redisPaused;

    @BeforeEach
    void setUp() {
        iam = new IamTestClient(context);
        useRedisBucketsAgain();
    }

    @AfterEach
    void restoreRedis() {
        if (redisPaused) {
            resumeRedis();
            redisPaused = false;
        }
        useRedisBucketsAgain();
    }

    @Test
    @Verifies("REQ-REL-008")
    void withRedisUnavailableTheSixthFailedLoginIsRejected() throws Exception {
        String email = iam.registerNewAccount();

        pauseRedis();
        redisPaused = true;
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }

        MvcResult rejected = login(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(
                        jsonPath("$.type").value(ProblemType.RATE_LIMITED.uri().toString()))
                .andReturn();
        assertThat(Long.parseLong(rejected.getResponse().getHeader("Retry-After")))
                .isBetween(1L, properties.limitFor(POLICY).window().toSeconds());
        assertThat(buckets.isDegraded()).isTrue();
        assertThat(degradedGauge()).isEqualTo(1.0);
    }

    @Test
    @Verifies("REQ-REL-008")
    void theLimitsReturnToRedisOnceItAnswersAgain() {
        String subject = "fallback-" + UUID.randomUUID() + "@example.com";

        pauseRedis();
        redisPaused = true;
        assertThat(rateLimiter.consume(POLICY, subject).allowed()).isTrue();
        assertThat(buckets.isDegraded()).isTrue();

        resumeRedis();
        redisPaused = false;
        buckets.retryNow();
        assertThat(rateLimiter.consume(POLICY, subject).allowed()).isTrue();

        assertThat(buckets.isDegraded()).isFalse();
        assertThat(degradedGauge()).isZero();
        assertThat(redis.hasKey(BucketRateLimiter.key(POLICY, keyedHasher.hmac(subject))))
                .isTrue();
    }

    /** Tries Redis on the next request and checks that the limiter is on the Redis buckets. */
    private void useRedisBucketsAgain() {
        buckets.retryNow();
        rateLimiter.check(POLICY, "probe-" + UUID.randomUUID() + "@example.com");
        assertThat(buckets.isDegraded()).as("rate limits on the Redis buckets").isFalse();
    }

    private ResultActions login(String email, String password) throws Exception {
        return iam.postJson(LOGIN, Map.of("email", email, "password", password), null);
    }

    private double degradedGauge() {
        return meterRegistry.get(FailoverBucketSource.DEGRADED_METRIC).gauge().value();
    }
}
