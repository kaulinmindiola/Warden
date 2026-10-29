package io.github.kaulinmindiola.warden.shared.ratelimit;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rate-limit settings under {@code warden.ratelimit} (SDD Appendix E). The limits have no default
 * in code: {@code application.yaml} holds the SRS values, and a missing limit stops the
 * application at startup instead of leaving an endpoint unprotected.
 *
 * <p>Each limit is a token bucket of {@code capacity} requests that refills in full once per
 * {@code window} (SDD V14-A2; window semantics recorded in ADR-014). Every value can be set from
 * the environment, for example {@code WARDEN_RATELIMIT_LOGINPERIP_CAPACITY}.
 *
 * @param redisCommandTimeout how long a Redis command of the rate limiter may take before it
 *     fails (SDD V23: 200 ms, so that an unavailable Redis is detected quickly)
 */
@ConfigurationProperties("warden.ratelimit")
public record RateLimitProperties(
        Limit loginFailuresPerEmail,
        Limit loginPerIp,
        Limit passwordResetPerEmail,
        Limit passwordResetPerIp,
        Limit refreshPerIp,
        Limit verificationResendPerAccount,
        Limit invitationsPerWorkspace,
        Limit invitationsPerInviter,
        @DefaultValue("200ms") Duration redisCommandTimeout) {

    public RateLimitProperties {
        requireConfigured(loginFailuresPerEmail, RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL);
        requireConfigured(loginPerIp, RateLimitPolicy.LOGIN_PER_IP);
        requireConfigured(passwordResetPerEmail, RateLimitPolicy.PASSWORD_RESET_PER_EMAIL);
        requireConfigured(passwordResetPerIp, RateLimitPolicy.PASSWORD_RESET_PER_IP);
        requireConfigured(refreshPerIp, RateLimitPolicy.REFRESH_PER_IP);
        requireConfigured(verificationResendPerAccount, RateLimitPolicy.VERIFICATION_RESEND_PER_ACCOUNT);
        requireConfigured(invitationsPerWorkspace, RateLimitPolicy.INVITATIONS_PER_WORKSPACE);
        requireConfigured(invitationsPerInviter, RateLimitPolicy.INVITATIONS_PER_INVITER);
        if (redisCommandTimeout == null || redisCommandTimeout.isZero() || redisCommandTimeout.isNegative()) {
            throw new IllegalArgumentException("warden.ratelimit.redis-command-timeout must be positive");
        }
    }

    /** The limit configured for {@code policy}. */
    public Limit limitFor(RateLimitPolicy policy) {
        return switch (Objects.requireNonNull(policy, "policy")) {
            case LOGIN_FAILURES_PER_EMAIL -> loginFailuresPerEmail;
            case LOGIN_PER_IP -> loginPerIp;
            case PASSWORD_RESET_PER_EMAIL -> passwordResetPerEmail;
            case PASSWORD_RESET_PER_IP -> passwordResetPerIp;
            case REFRESH_PER_IP -> refreshPerIp;
            case VERIFICATION_RESEND_PER_ACCOUNT -> verificationResendPerAccount;
            case INVITATIONS_PER_WORKSPACE -> invitationsPerWorkspace;
            case INVITATIONS_PER_INVITER -> invitationsPerInviter;
        };
    }

    private static void requireConfigured(Limit limit, RateLimitPolicy policy) {
        if (limit == null) {
            throw new IllegalArgumentException("warden.ratelimit." + policy.key() + " must be configured");
        }
    }

    /**
     * One limit: at most {@code capacity} requests, refilled in full every {@code window}.
     *
     * @param capacity requests admitted per window; at least 1
     * @param window length of the window; positive
     */
    public record Limit(int capacity, Duration window) {

        public Limit {
            if (capacity < 1) {
                throw new IllegalArgumentException("A rate limit capacity must be at least 1");
            }
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("A rate limit window must be positive");
            }
        }
    }
}
