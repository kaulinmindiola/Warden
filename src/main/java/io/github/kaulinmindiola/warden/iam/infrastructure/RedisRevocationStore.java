package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Revocation denylist in Redis: key {@code warden:revoked:{sid}} with the configured TTL. Redis
 * errors never propagate: lookups answer {@link State#UNKNOWN} so the caller falls back to
 * PostgreSQL, and failed writes are logged.
 */
@Component
public class RedisRevocationStore implements RevocationStore {

    static final String KEY_PREFIX = "warden:revoked:";

    private static final Logger log = LoggerFactory.getLogger(RedisRevocationStore.class);

    private final StringRedisTemplate redis;
    private final RevocationProperties properties;

    public RedisRevocationStore(StringRedisTemplate redis, RevocationProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void markRevoked(UUID sessionId) {
        try {
            redis.opsForValue().set(key(sessionId), "1", properties.ttl());
        } catch (RuntimeException unavailable) {
            log.warn(
                    "Could not record a session revocation in Redis ({}); PostgreSQL remains authoritative",
                    unavailable.getClass().getSimpleName());
        }
    }

    @Override
    public State state(UUID sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(sessionId))) ? State.REVOKED : State.NOT_REVOKED;
        } catch (RuntimeException unavailable) {
            log.warn(
                    "Could not read the revocation denylist in Redis ({}); checking PostgreSQL",
                    unavailable.getClass().getSimpleName());
            return State.UNKNOWN;
        }
    }

    static String key(UUID sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
