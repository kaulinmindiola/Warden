package io.github.kaulinmindiola.warden.iam.application;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The registration request is invalid. Carries one reason per field, in request order; never the
 * submitted values.
 */
public class InvalidRegistrationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> violations;

    public InvalidRegistrationException(Map<String, String> violations) {
        super("Invalid registration: " + violations.keySet());
        this.violations = Collections.unmodifiableMap(new LinkedHashMap<>(violations));
    }

    /** Field name to reason, for example {@code password → is too common}. */
    public Map<String, String> violations() {
        return violations == null ? Map.of() : violations;
    }
}
