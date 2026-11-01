package io.github.kaulinmindiola.warden.notification.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a notification goes to, as the event carries it (ADR-017).
 *
 * @param userId account of the recipient, or {@code null} for an address without account yet
 * @param email normalized e-mail address
 * @param fallbackLocale the language carried by the event, used when the user has chosen none
 */
public record NotificationRecipient(UUID userId, String email, String fallbackLocale) {

    public NotificationRecipient {
        Objects.requireNonNull(email, "email");
    }

    /** The e-mail address is left out so a recipient never leaks into logs. */
    @Override
    public String toString() {
        return "NotificationRecipient[userId=" + userId + ", email redacted, fallbackLocale=" + fallbackLocale + "]";
    }
}
