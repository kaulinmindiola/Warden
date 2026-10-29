package io.github.kaulinmindiola.warden.iam.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Revocation denylist settings (SDD Appendix E, V14-A7).
 *
 * @param ttl how long a revoked session stays in the denylist: the access-token lifetime plus the
 *     clock skew (600 s + 30 s), after which every token of the session has expired anyway
 * @param reconcileInterval how often a healthy instance rewrites the recent revocations from
 *     PostgreSQL into Redis, which bounds the gap left by a write lost on another instance
 *     (DRISK-01)
 */
@ConfigurationProperties("warden.revocation")
public record RevocationProperties(
        @DefaultValue("630s") Duration ttl,
        @DefaultValue("30s") Duration reconcileInterval) {

    public RevocationProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("warden.revocation.ttl must be positive");
        }
        if (reconcileInterval == null || reconcileInterval.isNegative() || reconcileInterval.isZero()) {
            throw new IllegalArgumentException("warden.revocation.reconcile-interval must be positive");
        }
    }
}
