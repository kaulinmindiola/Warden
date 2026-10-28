package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An invitation request has invalid fields. Carries one reason per field; never the values. */
public class InvalidInvitationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> violations;

    public InvalidInvitationException(Map<String, String> violations) {
        super("Invalid invitation: " + violations.keySet());
        this.violations = Collections.unmodifiableMap(new LinkedHashMap<>(violations));
    }

    /** Field name to reason. */
    public Map<String, String> violations() {
        return violations == null ? Map.of() : violations;
    }
}
