package io.github.kaulinmindiola.warden.iam.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A normalized e-mail address: surrounding whitespace removed and lower-cased with {@link
 * Locale#ROOT}, so two values that differ only in case or surrounding whitespace are the same
 * address (REQ-FUNC-004).
 *
 * <p>{@link #toString()} does not reveal the address, so it cannot leak into logs by accident;
 * use {@link #value()} where the address is needed.
 *
 * @param value the normalized address
 */
public record EmailAddress(String value) {

    /** Maximum length of an address (RFC 5321 path limit minus the angle brackets). */
    public static final int MAX_LENGTH = 254;

    /** Maximum length of the part before the {@code @} (RFC 5321). */
    public static final int MAX_LOCAL_PART_LENGTH = 64;

    private static final Pattern FORMAT = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    public EmailAddress {
        Objects.requireNonNull(value, "value");
        if (!value.equals(normalize(value))) {
            throw new IllegalArgumentException("An EmailAddress must be built from a normalized value; use of()");
        }
        if (value.length() > MAX_LENGTH
                || !FORMAT.matcher(value).matches()
                || value.indexOf('@') > MAX_LOCAL_PART_LENGTH) {
            throw new InvalidEmailException();
        }
    }

    /**
     * Normalizes and validates a raw address.
     *
     * @throws InvalidEmailException if the value is missing or is not a well-formed address
     */
    public static EmailAddress of(String raw) {
        if (raw == null) {
            throw new InvalidEmailException();
        }
        return new EmailAddress(normalize(raw));
    }

    private static String normalize(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "EmailAddress[redacted]";
    }
}
