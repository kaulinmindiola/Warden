package io.github.kaulinmindiola.warden.iam.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single-use, expiring token for e-mail verification or password reset (BR-006, SDD V05).
 *
 * <p>A token is active while it is neither consumed nor invalidated and the current time is
 * before {@code expiresAt}; at {@code expiresAt} it is already expired. At most one token per user
 * and purpose is active: issuing a new one invalidates the previous ones (REQ-FUNC-007, 019).
 */
public class OneTimeToken {

    /** What a token is for, and how long it lives. */
    public enum Purpose {
        /** REQ-FUNC-005: 24 hours. */
        EMAIL_VERIFICATION(Duration.ofHours(24)),
        /** REQ-FUNC-019: 30 minutes. */
        PASSWORD_RESET(Duration.ofMinutes(30));

        private final Duration lifetime;

        Purpose(Duration lifetime) {
            this.lifetime = lifetime;
        }

        public Duration lifetime() {
            return lifetime;
        }
    }

    private final UUID id;
    private final UUID userId;
    private final Purpose purpose;
    private final TokenHash hash;
    private final Instant expiresAt;
    private Instant consumedAt;
    private Instant invalidatedAt;

    private OneTimeToken(UUID id, UUID userId, Purpose purpose, TokenHash hash, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.hash = Objects.requireNonNull(hash, "hash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** A new active token that expires after the lifetime of its purpose. */
    public static OneTimeToken issue(UUID id, UUID userId, Purpose purpose, TokenHash hash, Instant now) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(now, "now");
        return new OneTimeToken(id, userId, purpose, hash, now.plus(purpose.lifetime()));
    }

    /** Whether the token can still be consumed at {@code now}. */
    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now");
        return consumedAt == null && invalidatedAt == null && now.isBefore(expiresAt);
    }

    /**
     * Uses the token.
     *
     * @throws InvalidOneTimeTokenException if it is consumed, invalidated or expired
     */
    public void consume(Instant now) {
        if (!isActive(now)) {
            throw new InvalidOneTimeTokenException();
        }
        consumedAt = now;
    }

    /** Invalidates the token because a newer one was issued. Has no effect if it was already used up. */
    public void invalidate(Instant now) {
        Objects.requireNonNull(now, "now");
        if (consumedAt == null && invalidatedAt == null) {
            invalidatedAt = now;
        }
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public Purpose purpose() {
        return purpose;
    }

    public TokenHash hash() {
        return hash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant consumedAt() {
        return consumedAt;
    }

    public Instant invalidatedAt() {
        return invalidatedAt;
    }
}
