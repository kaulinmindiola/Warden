package io.github.kaulinmindiola.warden.shared.ratelimit;

/**
 * The rate limits of Warden (SRS DEC-005, DEC-028, DEC-031). Each policy names what its subject
 * is; callers pass that value already normalized (for example the normalized e-mail address).
 *
 * <p>The {@link #key()} is the property name under {@code warden.ratelimit}, the {@code policy}
 * tag of the rejection metric and the policy segment of the Redis key. It never changes once
 * published, because dashboards and stored buckets depend on it.
 */
public enum RateLimitPolicy {

    /** Failed logins per normalized e-mail, existing or not (REQ-SEC-006). */
    LOGIN_FAILURES_PER_EMAIL("login-failures-per-email"),

    /** Login requests per client IP, whatever their result (REQ-SEC-007). */
    LOGIN_PER_IP("login-per-ip"),

    /** Password reset requests per normalized e-mail, existing or not (REQ-SEC-008). */
    PASSWORD_RESET_PER_EMAIL("password-reset-per-email"),

    /** Password reset requests per client IP (REQ-SEC-008). */
    PASSWORD_RESET_PER_IP("password-reset-per-ip"),

    /** Refresh requests per client IP (REQ-SEC-009). */
    REFRESH_PER_IP("refresh-per-ip"),

    /** Verification resends per account id (REQ-FUNC-007). */
    VERIFICATION_RESEND_PER_ACCOUNT("verification-resend-per-account"),

    /** Invitations created per workspace id (REQ-FUNC-033). */
    INVITATIONS_PER_WORKSPACE("invitations-per-workspace"),

    /** Invitations created per inviting user id, across all workspaces (REQ-FUNC-033). */
    INVITATIONS_PER_INVITER("invitations-per-inviter");

    private final String key;

    RateLimitPolicy(String key) {
        this.key = key;
    }

    /** Stable kebab-case name of the policy. */
    public String key() {
        return key;
    }
}
