package io.github.kaulinmindiola.warden.notification.domain;

import java.util.Objects;

/**
 * One e-mail as handed to a provider (SDD V09 wire contract).
 *
 * @param idempotencyKey the delivery id, so a provider can recognize a resend of the same delivery
 * @param to the recipient's address
 * @param subject the rendered subject
 * @param text the rendered plain-text body, which may contain a link token
 */
public record EmailMessage(String idempotencyKey, String to, String subject, String text) {

    public EmailMessage {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(text, "text");
    }

    /** The address and the content are left out so a message never leaks into logs. */
    @Override
    public String toString() {
        return "EmailMessage[idempotencyKey=" + idempotencyKey + ", recipient and content redacted]";
    }
}
