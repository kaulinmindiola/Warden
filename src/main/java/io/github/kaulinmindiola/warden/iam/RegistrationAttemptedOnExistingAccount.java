package io.github.kaulinmindiola.warden.iam;

import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import java.time.Instant;

/**
 * Payload of {@code iam.RegistrationAttemptedOnExistingAccount} (SDD V10): someone tried to
 * register an e-mail that already has an account, so its holder is told (REQ-FUNC-002).
 *
 * @param recipient holder of the existing account, with their registration locale
 * @param occurredAt when the attempt happened
 */
public record RegistrationAttemptedOnExistingAccount(Recipient recipient, Instant occurredAt) {

    /** Event type stored in the outbox. */
    public static final String TYPE = "iam.RegistrationAttemptedOnExistingAccount";
}
