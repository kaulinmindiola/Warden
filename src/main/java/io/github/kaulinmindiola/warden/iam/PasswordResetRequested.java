package io.github.kaulinmindiola.warden.iam;

import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import java.time.Instant;

/**
 * Payload of {@code iam.PasswordResetRequested} (SDD V10): the holder of an account asked for a
 * password reset link. The one-time token travels sealed (ADR-011).
 *
 * @param recipient holder of the account, with their registration locale
 * @param secret the sealed one-time token
 * @param expiresAt when the link expires
 */
public record PasswordResetRequested(Recipient recipient, SealedSecret secret, Instant expiresAt) {

    /** Event type stored in the outbox. */
    public static final String TYPE = "iam.PasswordResetRequested";
}
