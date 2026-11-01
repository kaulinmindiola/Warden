package io.github.kaulinmindiola.warden.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A workspace delivery as an ADMIN sees it (REQ-FUNC-059): its life cycle, never its content. The
 * rendered subject, body, variables and link token are left out, since they may carry a token
 * (REQ-SEC-014).
 *
 * @param recipientEmail the address it is sent to
 * @param providerUsed the provider called last, {@code NONE}, or {@code null} before any attempt
 * @param lastErrorClass the cause of the last failure, or {@code null}
 */
public record DeliveryView(
        UUID id,
        String templateKey,
        String recipientEmail,
        DeliveryStatus status,
        int attempts,
        String providerUsed,
        String lastErrorClass,
        Instant createdAt,
        Instant nextAttemptAt,
        Instant sentAt) {

    public DeliveryView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(templateKey, "templateKey");
        Objects.requireNonNull(recipientEmail, "recipientEmail");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    }

    /** The address is left out of logs. */
    @Override
    public String toString() {
        return "DeliveryView[id=" + id + ", templateKey=" + templateKey + ", status=" + status + "]";
    }
}
