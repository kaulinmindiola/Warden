package io.github.kaulinmindiola.warden.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A login session, identified in access tokens by the {@code sid} claim (SDD V05).
 *
 * <p>A session is usable while it is not revoked and the current time is before its absolute
 * expiry, 30 days after creation (DEC-009). Once revoked, it never becomes active again.
 */
@Entity
@Table(name = "auth_session")
public class AuthSession {

    /** Absolute lifetime of a session, whatever its activity (DEC-009). */
    public static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);

    /** Maximum length, in code points, of the stored User-Agent summary. */
    public static final int MAX_USER_AGENT_SUMMARY = 200;

    /** Why a session was revoked. */
    public enum RevokeReason {
        LOGOUT,
        TERMINATED,
        REUSE,
        PASSWORD_RESET
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "user_agent_summary")
    private String userAgentSummary;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason")
    private RevokeReason revokeReason;

    /** For JPA only. */
    protected AuthSession() {}

    private AuthSession(UUID id, UUID userId, Instant now, String userAgentSummary) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.lastUsedAt = now;
        this.absoluteExpiresAt = now.plus(ABSOLUTE_LIFETIME);
        this.userAgentSummary = userAgentSummary;
    }

    /**
     * A new session created by a successful login.
     *
     * @param userAgent the raw {@code User-Agent} header, or {@code null}
     */
    public static AuthSession start(UUID id, UUID userId, Instant now, String userAgent) {
        return new AuthSession(id, userId, now, summarizeUserAgent(userAgent));
    }

    /**
     * Removes control characters and limits the length, so the summary is safe to store and to
     * show in the session list.
     *
     * @return the summary, or {@code null} if nothing printable remains
     */
    public static String summarizeUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        String cleaned = userAgent.replaceAll("\\p{Cntrl}", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) <= MAX_USER_AGENT_SUMMARY) {
            return cleaned;
        }
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_USER_AGENT_SUMMARY));
    }

    /** Whether tokens of this session are accepted at {@code now}. */
    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now");
        return revokedAt == null && now.isBefore(absoluteExpiresAt);
    }

    /** Revokes the session. Revoking again keeps the first time and reason. */
    public void revoke(RevokeReason reason, Instant now) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        if (revokedAt == null) {
            revokedAt = now;
            revokeReason = reason;
        }
    }

    /** Records a use of the session (refresh). */
    public void markUsed(Instant now) {
        lastUsedAt = Objects.requireNonNull(now, "now");
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant absoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public String userAgentSummary() {
        return userAgentSummary;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public RevokeReason revokeReason() {
        return revokeReason;
    }
}
