package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.iam.infrastructure.RedisRevocationStore;
import io.github.kaulinmindiola.warden.iam.infrastructure.RevocationReconciler;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * REQ-REL-007 with Redis really unreachable (SDD V14-A7, ADR-007): the shared Redis container is
 * frozen, so every command fails at the 200 ms timeout. Revocation checks fall back to PostgreSQL,
 * and recovery rewrites the revocations made during the outage before leaving degraded mode.
 */
class RevocationDegradationIT extends IntegrationTest {

    private static final String SESSIONS = API + "/auth/sessions";
    private static final String LOGOUT = API + "/auth/logout";
    private static final String DEGRADED_GAUGE = "warden.revocation.degraded";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RedisRevocationStore store;

    @Autowired
    private RevocationReconciler reconciler;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meterRegistry;

    private IamTestClient iam;
    private boolean redisPaused;

    @BeforeEach
    void setUp() {
        iam = new IamTestClient(context);
        assertThat(reconciler.recover()).as("store healthy before the test").isTrue();
    }

    @AfterEach
    void restoreRedis() {
        if (redisPaused) {
            resumeRedis();
            redisPaused = false;
        }
        assertThat(reconciler.recover()).as("store healthy after the test").isTrue();
    }

    @Test
    @Verifies("REQ-REL-007")
    void withRedisUnavailableRevokedSessionsAreRejectedAndActiveOnesAccepted() throws Exception {
        String email = iam.registerNewAccount();
        String revoked = iam.login(email);
        String active = iam.login(email);
        iam.postJson(LOGOUT, Map.of(), revoked).andExpect(status().isNoContent());

        pauseRedis();
        redisPaused = true;

        iam.get(SESSIONS, revoked).andExpect(status().isUnauthorized());
        iam.get(SESSIONS, active).andExpect(status().isOk());
        assertThat(store.isDegraded()).isTrue();
        assertThat(degradedGauge()).isEqualTo(1.0);
    }

    @Test
    @Verifies("REQ-REL-007")
    void aSessionRevokedDuringTheOutageStaysRejectedWhenRedisReturns() throws Exception {
        String email = iam.registerNewAccount();
        String revoked = iam.login(email);
        String active = iam.login(email);

        pauseRedis();
        redisPaused = true;
        iam.postJson(LOGOUT, Map.of(), revoked).andExpect(status().isNoContent());
        iam.get(SESSIONS, revoked).andExpect(status().isUnauthorized());

        resumeRedis();
        redisPaused = false;
        assertThat(store.isDegraded()).as("still degraded until recovery").isTrue();
        assertThat(reconciler.recover()).isTrue();

        assertThat(store.isDegraded()).isFalse();
        assertThat(degradedGauge()).isZero();
        assertThat(redis.hasKey(denylistKey(revoked))).isTrue();
        iam.get(SESSIONS, revoked).andExpect(status().isUnauthorized());
        iam.get(SESSIONS, active).andExpect(status().isOk());
    }

    @Test
    @Verifies("REQ-REL-007")
    void periodicReconciliationRestoresARevocationMissingFromRedis() throws Exception {
        String email = iam.registerNewAccount();
        String revoked = iam.login(email);
        iam.postJson(LOGOUT, Map.of(), revoked).andExpect(status().isNoContent());
        // Simulates a denylist write lost on another instance (DRISK-01).
        redis.delete(denylistKey(revoked));
        clock.advance(Duration.ofSeconds(100));

        assertThat(reconciler.reconcile()).isTrue();

        assertThat(redis.hasKey(denylistKey(revoked))).isTrue();
        assertThat(redis.getExpire(denylistKey(revoked))).isBetween(1L, 530L);
        iam.get(SESSIONS, revoked).andExpect(status().isUnauthorized());
    }

    @Test
    void aRevocationOlderThanTheDenylistTtlIsNotRewritten() throws Exception {
        String email = iam.registerNewAccount();
        String revoked = iam.login(email);
        iam.postJson(LOGOUT, Map.of(), revoked).andExpect(status().isNoContent());
        redis.delete(denylistKey(revoked));
        clock.advance(Duration.ofSeconds(631));

        assertThat(reconciler.reconcile()).isTrue();

        assertThat(redis.hasKey(denylistKey(revoked))).isFalse();
    }

    private double degradedGauge() {
        return meterRegistry.get(DEGRADED_GAUGE).gauge().value();
    }

    private static String denylistKey(String accessToken) {
        return "warden:revoked:" + IamTestClient.sessionIdOf(accessToken);
    }
}
