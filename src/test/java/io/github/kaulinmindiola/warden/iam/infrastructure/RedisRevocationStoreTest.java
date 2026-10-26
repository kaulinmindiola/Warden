package io.github.kaulinmindiola.warden.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRevocationStoreTest {

    private static final RevocationProperties PROPERTIES = new RevocationProperties(Duration.ofSeconds(630));

    @Test
    @SuppressWarnings("unchecked")
    void marksARevokedSessionWithTheConfiguredTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        UUID sessionId = UUID.randomUUID();

        new RedisRevocationStore(redis, PROPERTIES).markRevoked(sessionId);

        verify(values).set("warden:revoked:" + sessionId, "1", Duration.ofSeconds(630));
    }

    @Test
    void answersRevokedOrNotRevokedWhileRedisIsReachableAndUnknownWhenItFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        UUID revoked = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        when(redis.hasKey("warden:revoked:" + revoked)).thenReturn(true);
        when(redis.hasKey("warden:revoked:" + active)).thenReturn(false);
        when(redis.hasKey("warden:revoked:" + failing))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        RedisRevocationStore store = new RedisRevocationStore(redis, PROPERTIES);

        assertThat(store.state(revoked)).isEqualTo(RevocationStore.State.REVOKED);
        assertThat(store.state(active)).isEqualTo(RevocationStore.State.NOT_REVOKED);
        assertThat(store.state(failing)).isEqualTo(RevocationStore.State.UNKNOWN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedWriteDoesNotPropagate() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(values)
                .set(anyString(), eq("1"), any(Duration.class));

        assertThatCode(() -> new RedisRevocationStore(redis, PROPERTIES).markRevoked(UUID.randomUUID()))
                .doesNotThrowAnyException();
    }
}
