package io.github.kaulinmindiola.warden.iam.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps the Redis denylist complete (SDD V14-A7, ADR-007).
 *
 * <ul>
 *   <li><b>Recovery:</b> every 5 seconds while the store is degraded, if Redis answers a PING, the
 *       revocations of the last TTL are rewritten from PostgreSQL; the store leaves degraded mode
 *       only after that. Sessions revoked during the outage therefore stay rejected when Redis
 *       returns, even if Redis lost its data.
 *   <li><b>Periodic reconciliation:</b> every {@code warden.revocation.reconcile-interval} (30 s)
 *       while healthy, the same rewrite repairs a revocation whose Redis write was lost on another
 *       instance (DRISK-01).
 * </ul>
 *
 * <p>Runs in every instance; rewriting the same keys twice is harmless. {@link
 * RevocationReconcileSchedule} ticks it every 5 seconds while workers are switched on; tests, where
 * they are off, call {@link #recover()} and {@link #reconcile()} directly.
 */
@Component
public class RevocationReconciler {

    private static final Logger log = LoggerFactory.getLogger(RevocationReconciler.class);

    private final RedisRevocationStore store;
    private final AuthSessionRepository sessions;
    private final RevocationProperties properties;
    private final Clock clock;
    private volatile Instant lastReconciled = Instant.MIN;

    public RevocationReconciler(
            RedisRevocationStore store, AuthSessionRepository sessions, RevocationProperties properties, Clock clock) {
        this.store = store;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    /** Recovers a degraded store, or reconciles a healthy one when its interval has passed. */
    void tick() {
        if (store.isDegraded()) {
            recover();
        } else if (!clock.instant().isBefore(lastReconciled.plus(properties.reconcileInterval()))) {
            reconcile();
        }
    }

    /**
     * Leaves degraded mode if Redis is reachable and the recent revocations could be rewritten.
     *
     * @return whether the store is healthy afterwards
     */
    public boolean recover() {
        if (!store.isDegraded()) {
            return true;
        }
        return store.ping() && reconcile();
    }

    /**
     * Rewrites into Redis every session revoked within the denylist TTL. Never throws: a PostgreSQL
     * failure is logged and retried on the next tick.
     *
     * @return whether every revocation was written and the store is healthy
     */
    public boolean reconcile() {
        Instant now = clock.instant();
        long failuresSeen = store.failureCount();
        List<RevokedSession> recent;
        try {
            recent = sessions.findRevokedSince(now.minus(properties.ttl()));
        } catch (RuntimeException unavailable) {
            log.warn(
                    "Could not read recent revocations from PostgreSQL ({}); retrying later",
                    unavailable.getClass().getSimpleName());
            return false;
        }
        boolean complete = store.rewrite(recent, now, failuresSeen);
        if (complete) {
            lastReconciled = now;
        }
        return complete;
    }
}
