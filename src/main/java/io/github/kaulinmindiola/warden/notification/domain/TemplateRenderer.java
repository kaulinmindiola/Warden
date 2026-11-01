package io.github.kaulinmindiola.warden.notification.domain;

import java.util.Map;

/**
 * Validates and renders templates without any logic (REQ-SEC-013, SDD V09 and V14-A6): a template
 * is plain text with {@code {{variable}}} placeholders, and values are inserted as literal text.
 */
public interface TemplateRenderer {

    /**
     * Checks that subject and body reference only variables declared for the key and contain no
     * other {@code {{ }}} construct.
     *
     * @throws TemplateValidationException listing every violation found
     */
    void validate(String key, String subject, String body);

    /**
     * Replaces each placeholder of the template by the value of its variable, in a single pass.
     * Inserted values are never scanned again, so a value that looks like a placeholder stays
     * literal.
     *
     * @throws IllegalArgumentException if the template references a variable with no value
     */
    RenderedMessage render(Template template, Map<String, String> values);
}
