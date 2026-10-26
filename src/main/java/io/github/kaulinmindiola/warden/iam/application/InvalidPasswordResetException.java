package io.github.kaulinmindiola.warden.iam.application;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A password reset request or confirmation has invalid fields. Carries one reason per field;
 * never the submitted values.
 */
public class InvalidPasswordResetException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> violations;

    public InvalidPasswordResetException(Map<String, String> violations) {
        super("Invalid password reset: " + violations.keySet());
        this.violations = Collections.unmodifiableMap(new LinkedHashMap<>(violations));
    }

    /** Field name to reason, for example {@code newPassword → is too common}. */
    public Map<String, String> violations() {
        return violations == null ? Map.of() : violations;
    }
}
