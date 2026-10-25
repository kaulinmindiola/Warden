package io.github.kaulinmindiola.warden.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A registered user (SDD V05). The e-mail is unique after normalization; the database enforces it
 * with a unique constraint. Accounts start with an unverified e-mail; an unverified account can
 * still log in (REQ-FUNC-008) but cannot create workspaces or accept invitations (BR-009, BR-010).
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    private UUID id;

    @Convert(converter = EmailAddressConverter.class)
    @Column(name = "email_normalized", nullable = false)
    private EmailAddress email;

    @Convert(converter = PasswordHashConverter.class)
    @Column(name = "password_hash", nullable = false)
    private PasswordHash passwordHash;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_locale", nullable = false)
    private SupportedLocale registrationLocale;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected UserAccount() {}

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
