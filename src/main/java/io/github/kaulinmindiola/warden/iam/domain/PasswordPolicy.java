package io.github.kaulinmindiola.warden.iam.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Password rules of REQ-FUNC-003 (DEC-008): between 12 and 128 characters and not in the
 * common-password list. There are no composition rules (no required digits, symbols or upper-case
 * letters).
 *
 * <p>Length is counted in Unicode code points, so a character outside the Basic Multilingual Plane
 * counts once. The common-password check ignores case.
 */
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private final Set<String> commonPasswords;

    /**
     * @param commonPasswords common passwords, already lower-cased with {@link Locale#ROOT}
     */
    public PasswordPolicy(Set<String> commonPasswords) {
        this.commonPasswords = Set.copyOf(Objects.requireNonNull(commonPasswords, "commonPasswords"));
    }

    /**
     * Checks a candidate password.
     *
     * @throws InvalidPasswordException with the first rule the password breaks
     */
    public void check(String password) {
        Objects.requireNonNull(password, "password");
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            throw new InvalidPasswordException(InvalidPasswordException.Reason.TOO_SHORT);
        }
        if (length > MAX_LENGTH) {
            throw new InvalidPasswordException(InvalidPasswordException.Reason.TOO_LONG);
        }
        if (commonPasswords.contains(password.toLowerCase(Locale.ROOT))) {
            throw new InvalidPasswordException(InvalidPasswordException.Reason.TOO_COMMON);
        }
    }

    /** Number of entries in the common-password list. */
    public int commonPasswordCount() {
        return commonPasswords.size();
    }
}
