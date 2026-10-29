package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Revocation denylist in Redis: key {@code warden:revoked:{sid}} with the configured TTL, and the
 * degraded mode of SDD V14-A7 (ADR-007). Redis errors never propagate.
 *
 * <ul>
 *   <li>The first Redis error puts this instance in degraded mode. While degraded, lookups answer
 *       {@link State#UNKNOWN} at once, without waiting for Redis, so the caller asks PostgreSQL.
 *   <li>Revocations are always written, degraded or not: if Redis is back, the write lands; if not,
 *       it fails fast and PostgreSQL already holds the truth.
 *   <li>{@link #rewrite} copies recent revocations from PostgreSQL into Redis. It leaves degraded
 *       mode only if every write succeeded and no other Redis error happened since the caller
 *       took {@link #failureCount()}, so a revocation lost during the rewrite is never forgotten.
 * </ul>
 *
 * <p>The gauge {@code warden_revocation_degraded} is 1 while this instance is degraded.
 */
@Component
public class RedisRevocationStore implements RevocationStore {

    static final String KEY_PREFIX = "warden:revoked:";
    static final String DEGRADED_METRIC = "warden.revocation.degraded";

    private static final Logger log = LoggerFactory.getLogger(RedisRevocationStore.class);

    private final StringRedisTemplate redis;
    private final RevocationProperties properties;
    // Guards the degraded flag together with the failure count, so a failure is never lost while
    // a rewrite decides whether to leave degraded mode. ReentrantLock, not synchronized: a virtual
    // thread blocked in synchronized pins its carrier.
    private final ReentrantLock modeLock = new ReentrantLock();
    private volatile boolean degraded;
    private volatile long failures;

    public RedisRevocationStore(
            StringRedisTemplate redis, RevocationProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        Gauge.builder(DEGRADED_METRIC, this, store -> store.isDegraded() ? 1.0 : 0.0)
                .description("1 while revocation checks fall back to PostgreSQL because Redis failed")
                .register(meterRegistry);
    }

    @Override
    public void markRevoked(UUID sessionId) {
        try {
            redis.opsForValue().set(key(sessionId), "1", properties.ttl());
        } catch (RuntimeException unavailable) {
            enterDegraded(unavailable);
        }
    }

    @Override
    public State state(UUID sessionId) {
        if (degraded) {
            return State.UNKNOWN;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(sessionId))) ? State.REVOKED : State.NOT_REVOKED;
        } catch (RuntimeException unavailable) {
            enterDegraded(unavailable);
            return State.UNKNOWN;
        }
    }

    /** Whether this instance is checking revocations in PostgreSQL because Redis failed. */
    public boolean isDegraded() {
        return degraded;
    }

    /** Number of Redis errors seen so far; read before a rewrite and passed to it. */
    public long failureCount() {
        return failures;
    }

    /** Whether Redis answers a {@code PING}; a failure enters degraded mode. */
    public boolean ping() {
        try {
            redis.execute((RedisCallback<String>) connection -> connection.ping());
            return true;
        } catch (RuntimeException unavailable) {
            enterDegraded(unavailable);
            return false;
        }
    }

    /**
     * Writes each revocation with the TTL it has left: {@code ttl - (now - revokedAt)}. Expired ones
     * are skipped. Leaves degraded mode only if every write succeeded and no Redis error happened
     * after {@code failuresSeen} was read.
     *
     * @return whether the denylist is complete and the instance is healthy
     */
    public boolean rewrite(List<RevokedSession> revoked, Instant now, long failuresSeen) {
        for (RevokedSession session : revoked) {
            Duration remaining = properties.ttl().minus(Duration.between(session.revokedAt(), now));
            if (remaining.isNegative() || remaining.isZero()) {
                continue;
            }
            try {
                redis.opsForValue().set(key(session.sessionId()), "1", remaining);
            } catch (RuntimeException unavailable) {
                enterDegraded(unavailable);
                return false;
            }
        }
        modeLock.lock();
        try {
            if (failures != failuresSeen) {
                return false;
            }
            if (degraded) {
                degraded = false;
                log.info("Redis is reachable again; {} recent revocations restored", revoked.size());
            }
            return true;
        } finally {
            modeLock.unlock();
        }
    }

    private void enterDegraded(RuntimeException cause) {
        modeLock.lock();
        try {
            failures++;
            if (!degraded) {
                degraded = true;
                log.warn(
                        "Redis failed ({}); revocation checks fall back to PostgreSQL until it recovers",
                        cause.getClass().getSimpleName());
            }
        } finally {
            modeLock.unlock();
        }
    }

    static String key(UUID sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
