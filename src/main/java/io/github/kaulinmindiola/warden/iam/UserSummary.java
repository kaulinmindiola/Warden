package io.github.kaulinmindiola.warden.iam;

import java.util.Objects;
import java.util.UUID;

/**
 * What other modules may know about a user (SDD V09).
 *
 * @param id user id
 * @param email normalized e-mail address
 * @param locale registration locale tag, {@code es} or {@code en}
 * @param emailVerified whether the e-mail address is verified
 */
public record UserSummary(UUID id, String email, String locale, boolean emailVerified) {

    public UserSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(locale, "locale");
    }

    /** The e-mail address is left out so a summary never leaks into logs. */
    @Override
    public String toString() {
        return "UserSummary[id=" + id + ", email redacted, locale=" + locale + ", emailVerified=" + emailVerified + "]";
    }
}
