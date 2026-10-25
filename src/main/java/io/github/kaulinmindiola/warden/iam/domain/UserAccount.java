package io.github.kaulinmindiola.warden.iam.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A registered user (SDD V05). The e-mail is unique after normalization; the database enforces it
 * with a unique constraint. Accounts start with an unverified e-mail; an unverified account can
 * still log in (REQ-FUNC-008) but cannot create workspaces or accept invitations (BR-009, BR-010).
 */
public class UserAccount {

    private final UUID id;
    private final EmailAddress email;
    private PasswordHash passwordHash;
    private Instant emailVerifiedAt;
    private final SupportedLocale registrationLocale;
    private final Instant createdAt;

    private UserAccount(
            UUID id,
            EmailAddress email,
            PasswordHash passwordHash,
            Instant emailVerifiedAt,
            SupportedLocale registrationLocale,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.emailVerifiedAt = emailVerifiedAt;
        this.registrationLocale = Objects.requireNonNull(registrationLocale, "registrationLocale");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** A new account with an unverified e-mail (REQ-FUNC-001). */
    public static UserAccount register(
            UUID id, EmailAddress email, PasswordHash passwordHash, SupportedLocale locale, Instant now) {
        return new UserAccount(id, email, passwordHash, null, locale, now);
    }

    /**
     * Marks the e-mail as verified. Verifying again keeps the first verification time.
     *
     * @param now current time
     */
    public void verifyEmail(Instant now) {
        Objects.requireNonNull(now, "now");
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
        }
    }

    /** Replaces the password hash (password reset, rehash with stronger parameters). */
    public void changePassword(PasswordHash newHash) {
        this.passwordHash = Objects.requireNonNull(newHash, "newHash");
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public UUID id() {
        return id;
    }

    public EmailAddress email() {
        return email;
    }

    public PasswordHash passwordHash() {
        return passwordHash;
    }

    public Instant emailVerifiedAt() {
        return emailVerifiedAt;
    }

    public SupportedLocale registrationLocale() {
        return registrationLocale;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
