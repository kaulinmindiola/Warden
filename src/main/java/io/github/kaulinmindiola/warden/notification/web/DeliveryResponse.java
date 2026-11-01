package io.github.kaulinmindiola.warden.notification.web;

import io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus;
import io.github.kaulinmindiola.warden.notification.domain.DeliveryView;
import java.time.Instant;
import java.util.UUID;

/**
 * A workspace delivery in the listing (REQ-FUNC-059). It carries no subject, body, variables or
 * link: they may hold a token (REQ-SEC-014).
 */
public record DeliveryResponse(
        UUID id,
        String templateKey,
        String recipient,
        DeliveryStatus status,
        int attempts,
        String providerUsed,
        String lastErrorClass,
        Instant createdAt,
        Instant nextAttemptAt,
        Instant sentAt) {

    static DeliveryResponse from(DeliveryView view) {
        return new DeliveryResponse(
                view.id(),
                view.templateKey(),
                view.recipientEmail(),
                view.status(),
                view.attempts(),
                view.providerUsed(),
                view.lastErrorClass(),
                view.createdAt(),
                view.nextAttemptAt(),
                view.sentAt());
    }
}
