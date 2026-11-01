package io.github.kaulinmindiola.warden.notification.domain;

import java.util.Objects;

/**
 * One stored version of a template: plain-text subject and body with {@code {{variable}}}
 * placeholders (DEC-040).
 *
 * @param key the template key, for example {@code workspace.invitation}
 * @param locale {@code es} or {@code en}
 * @param version the version, starting at 1; a delivery pins it at creation (REQ-FUNC-053)
 */
public record Template(String key, String locale, int version, String subject, String body) {

    public Template {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
        if (version < 1) {
            throw new IllegalArgumentException("Template version must be at least 1");
        }
    }
}
