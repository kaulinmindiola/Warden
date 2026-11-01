package io.github.kaulinmindiola.warden.notification.application;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A preferences update with invalid fields; nothing was changed. */
public class InvalidPreferencesException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> violations;

    public InvalidPreferencesException(Map<String, String> violations) {
        super("Invalid notification preferences: " + violations.keySet());
        this.violations = Collections.unmodifiableMap(new LinkedHashMap<>(violations));
    }

    /** Field name to reason. */
    public Map<String, String> violations() {
        return violations == null ? Map.of() : violations;
    }
}
