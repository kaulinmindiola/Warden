package io.github.kaulinmindiola.warden.iam.domain;

import java.util.Arrays;
import java.util.Optional;

/** Languages Warden sends e-mails in (REQ-FUNC-051). Spanish is the default. */
public enum SupportedLocale {
    ES("es"),
    EN("en");

    /** Locale used when a request does not choose one. */
    public static final SupportedLocale DEFAULT = ES;

    private final String tag;

    SupportedLocale(String tag) {
        this.tag = tag;
    }

    /** Language tag stored in the database and in events: {@code es} or {@code en}. */
    public String tag() {
        return tag;
    }

    /** The locale with exactly this tag, if Warden supports it. */
    public static Optional<SupportedLocale> fromTag(String tag) {
        return Arrays.stream(values()).filter(locale -> locale.tag.equals(tag)).findFirst();
    }
}
