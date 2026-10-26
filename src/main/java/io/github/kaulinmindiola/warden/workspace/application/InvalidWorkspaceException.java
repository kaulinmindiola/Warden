package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A workspace request has invalid fields. Carries one reason per field; never the values. */
public class InvalidWorkspaceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> violations;

    public InvalidWorkspaceException(Map<String, String> violations) {
        super("Invalid workspace request: " + violations.keySet());
        this.violations = Collections.unmodifiableMap(new LinkedHashMap<>(violations));
    }

    /** Field name to reason. */
    public Map<String, String> violations() {
        return violations == null ? Map.of() : violations;
    }
}
