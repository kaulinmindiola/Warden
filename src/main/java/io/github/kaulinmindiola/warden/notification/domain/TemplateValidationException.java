package io.github.kaulinmindiola.warden.notification.domain;

import java.util.List;

/**
 * A template that uses an undeclared variable or contains anything other than plain text and
 * {@code {{variable}}} placeholders (REQ-SEC-013). Each violation names the field and the problem,
 * never the offending text.
 */
public class TemplateValidationException extends RuntimeException {

    private final transient List<String> violations;

    public TemplateValidationException(List<String> violations) {
        super("Invalid template: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
