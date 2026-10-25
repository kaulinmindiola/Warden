package io.github.kaulinmindiola.warden.shared.outbox;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who a notification goes to, carried inside events so the notification module never queries
 * other modules (SDD V10, ADR-017).
 *
 * @param userId account of the recipient, or {@code null} when the address has no account yet
 * @param email normalized e-mail address
 * @param fallbackLocale {@code es} or {@code en}, used when the recipient has no locale setting
 */
public record Recipient(UUID userId, String email, String fallbackLocale) {

    private static final Set<String> LOCALES = Set.of("es", "en");

    public Recipient {
        Objects.requireNonNull(email, "email");
        if (!LOCALES.contains(fallbackLocale)) {
            throw new IllegalArgumentException("The fallback locale must be es or en");
        }
    }

    /** The e-mail address is left out so a recipient never leaks into logs. */
    @Override
    public String toString() {
        return "Recipient[userId=" + userId + ", email redacted, fallbackLocale=" + fallbackLocale + "]";
    }
}
