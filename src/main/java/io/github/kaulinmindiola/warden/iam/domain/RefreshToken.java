package io.github.kaulinmindiola.warden.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A refresh token of a session (SDD V05). Only its hash is stored. It is used at most once and
 * expires after 7 days of inactivity or at the session's absolute expiry, whichever comes first
 * (DEC-009).
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    /** Lifetime of a refresh token without use (DEC-009). */
    public static final Duration IDLE_LIFETIME = Duration.ofDays(7);

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Convert(converter = TokenHashConverter.class)
    @Column(name = "token_hash", nullable = false)
    private TokenHash hash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    /** For JPA only. */
    protected RefreshToken() {}

    private RefreshToken(UUID id, UUID sessionId, TokenHash hash, Instant issuedAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.hash = Objects.requireNonNull(hash, "hash");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** A new refresh token for the session, expiring at {@code min(now + 7 days, absolute expiry)}. */
    public static RefreshToken issue(UUID id, AuthSession session, TokenHash hash, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        Instant idleExpiry = now.plus(IDLE_LIFETIME);
        Instant expiresAt = idleExpiry.isBefore(session.absoluteExpiresAt()) ? idleExpiry : session.absoluteExpiresAt();
        return new RefreshToken(id, session.id(), hash, now, expiresAt);
    }

    public UUID id() {
        return id;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public TokenHash hash() {
        return hash;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant usedAt() {
        return usedAt;
    }
}
