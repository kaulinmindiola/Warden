package io.github.kaulinmindiola.warden.iam;

import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import java.time.Instant;

/**
 * Payload of {@code iam.PasswordChanged} (SDD V10): the password of an account was replaced with a
 * reset link and all its sessions were revoked (REQ-FUNC-020).
 *
 * @param recipient holder of the account, with their registration locale
 * @param occurredAt when the password changed
 */
public record PasswordChanged(Recipient recipient, Instant occurredAt) {

    /** Event type stored in the outbox. */
    public static final String TYPE = "iam.PasswordChanged";
}
