package io.github.kaulinmindiola.warden.iam;

import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import java.time.Instant;

/**
 * Payload of {@code iam.EmailVerificationRequested} (SDD V10): an account needs a verification
 * link. The one-time token travels sealed (ADR-011).
 *
 * @param recipient holder of the account
 * @param secret the sealed one-time token
 * @param expiresAt when the link expires
 */
public record EmailVerificationRequested(Recipient recipient, SealedSecret secret, Instant expiresAt) {

    /** Event type stored in the outbox. */
    public static final String TYPE = "iam.EmailVerificationRequested";
}
