package io.github.kaulinmindiola.warden.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRevocationStoreTest {

    private static final Duration TTL = Duration.ofSeconds(630);
    private static final RevocationProperties PROPERTIES = new RevocationProperties(TTL, Duration.ofSeconds(30));
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry registry;
    private RedisRevocationStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        registry = new SimpleMeterRegistry();
        store = new RedisRevocationStore(redis, PROPERTIES, registry);
    }

    @Test
    void marksARevokedSessionWithTheConfiguredTtl() {
        UUID sessionId = UUID.randomUUID();

        store.markRevoked(sessionId);

        verify(values).set("warden:revoked:" + sessionId, "1", TTL);
    }

    @Test
    void answersRevokedOrNotRevokedWhileRedisIsReachableAndUnknownWhenItFails() {
        UUID revoked = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        when(redis.hasKey("warden:revoked:" + revoked)).thenReturn(true);
        when(redis.hasKey("warden:revoked:" + active)).thenReturn(false);
        when(redis.hasKey("warden:revoked:" + failing)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(store.state(revoked)).isEqualTo(RevocationStore.State.REVOKED);
        assertThat(store.state(active)).isEqualTo(RevocationStore.State.NOT_REVOKED);
        assertThat(store.state(failing)).isEqualTo(RevocationStore.State.UNKNOWN);
    }

    @Test
    void aFailedWriteDoesNotPropagateAndEntersDegradedMode() {
        failWrites();

        assertThatCode(() -> store.markRevoked(UUID.randomUUID())).doesNotThrowAnyException();
        assertThat(store.isDegraded()).isTrue();
    }

    @Test
    void whileDegradedLookupsAnswerUnknownWithoutCallingRedis() {
        UUID first = UUID.randomUUID();
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        store.state(first);

        assertThat(store.state(UUID.randomUUID())).isEqualTo(RevocationStore.State.UNKNOWN);
        assertThat(store.state(UUID.randomUUID())).isEqualTo(RevocationStore.State.UNKNOWN);
        verify(redis, times(1)).hasKey(anyString());
    }

    @Test
    void writesAreStillAttemptedWhileDegraded() {
        degrade();
        UUID sessionId = UUID.randomUUID();

        store.markRevoked(sessionId);

        verify(values).set("warden:revoked:" + sessionId, "1", TTL);
    }

    @Test
    void aRewriteGivesEachRevocationTheTtlItHasLeftAndSkipsExpiredOnes() {
        UUID recent = UUID.randomUUID();
        UUID expired = UUID.randomUUID();

        boolean complete = store.rewrite(
                List.of(new RevokedSession(recent, NOW.minusSeconds(100)), new RevokedSession(expired, NOW.minus(TTL))),
                NOW,
                store.failureCount());

        assertThat(complete).isTrue();
        verify(values).set("warden:revoked:" + recent, "1", Duration.ofSeconds(530));
        verify(values, never()).set(eq("warden:revoked:" + expired), eq("1"), any(Duration.class));
    }

    @Test
    void aRewriteLeavesDegradedModeOnlyIfNoRedisErrorHappenedSinceItStarted() {
        degrade();
        long seenBeforeAnotherFailure = store.failureCount();
        failWrites();
        store.markRevoked(UUID.randomUUID());

        assertThat(store.rewrite(List.of(), NOW, seenBeforeAnotherFailure)).isFalse();
        assertThat(store.isDegraded()).isTrue();

        assertThat(store.rewrite(List.of(), NOW, store.failureCount())).isTrue();
        assertThat(store.isDegraded()).isFalse();
    }

    @Test
    void aRewriteThatCannotWriteStaysDegraded() {
        degrade();
        failWrites();

        boolean complete =
                store.rewrite(List.of(new RevokedSession(UUID.randomUUID(), NOW)), NOW, store.failureCount());

        assertThat(complete).isFalse();
        assertThat(store.isDegraded()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pingReportsWhetherRedisAnswersAndDegradesWhenItDoesNot() {
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
        assertThat(store.ping()).isTrue();
        assertThat(store.isDegraded()).isFalse();

        when(redis.execute(any(RedisCallback.class))).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(store.ping()).isFalse();
        assertThat(store.isDegraded()).isTrue();
    }

    @Test
    void theDegradedGaugeFollowsTheMode() {
        assertThat(gauge()).isZero();

        degrade();
        assertThat(gauge()).isEqualTo(1.0);

        store.rewrite(List.of(), NOW, store.failureCount());
        assertThat(gauge()).isZero();
    }

    private void degrade() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        store.state(UUID.randomUUID());
        assertThat(store.isDegraded()).isTrue();
    }

    private void failWrites() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(values)
                .set(anyString(), eq("1"), any(Duration.class));
    }

    private double gauge() {
        return registry.get(RedisRevocationStore.DEGRADED_METRIC).gauge().value();
    }
}
