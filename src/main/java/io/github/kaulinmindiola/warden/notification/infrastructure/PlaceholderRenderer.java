package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.kaulinmindiola.warden.notification.domain.RenderedMessage;
import io.github.kaulinmindiola.warden.notification.domain.Template;
import io.github.kaulinmindiola.warden.notification.domain.TemplateKey;
import io.github.kaulinmindiola.warden.notification.domain.TemplateRenderer;
import io.github.kaulinmindiola.warden.notification.domain.TemplateValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Literal placeholder substitution (SDD V14-A6). A dedicated renderer instead of a template engine:
 * engines such as Mustache offer sections, partials and expressions, which REQ-SEC-013 forbids.
 *
 * <p>A placeholder is {@code {{name}}}, with optional spaces inside the braces, where the name is a
 * letter followed by letters or digits. Anything else between double braces, and any stray pair
 * of opening or closing braces, is rejected by {@link #validate}.
 */
@Component
public class PlaceholderRenderer implements TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}");

    @Override
    public void validate(String key, String subject, String body) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
        TemplateKey templateKey = TemplateKey.fromKey(key)
                .orElseThrow(() -> new TemplateValidationException(List.of("key: unknown template key")));
        List<String> violations = new ArrayList<>();
        check("subject", subject, templateKey.variables(), violations);
        check("body", body, templateKey.variables(), violations);
        if (!violations.isEmpty()) {
            throw new TemplateValidationException(violations);
        }
    }

    @Override
    public RenderedMessage render(Template template, Map<String, String> values) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(values, "values");
        return new RenderedMessage(render(template.subject(), values), render(template.body(), values));
    }

    private static void check(String field, String text, Set<String> declared, List<String> violations) {
        Set<String> undeclared = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!declared.contains(name)) {
                undeclared.add(name);
            }
        }
        if (!undeclared.isEmpty()) {
            violations.add(field + ": undeclared variables " + undeclared);
        }
        String withoutPlaceholders = PLACEHOLDER.matcher(text).replaceAll("");
        if (withoutPlaceholders.contains("{{") || withoutPlaceholders.contains("}}")) {
            violations.add(field + ": only {{variable}} placeholders are allowed between double braces");
        }
    }

    /** One left-to-right pass; each value is appended as is and never matched again. */
    private static String render(String text, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder rendered = new StringBuilder(text.length());
        int copiedUpTo = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                throw new IllegalArgumentException("No value for template variable " + name);
            }
            rendered.append(text, copiedUpTo, matcher.start()).append(value);
            copiedUpTo = matcher.end();
        }
        return rendered.append(text, copiedUpTo, text.length()).toString();
    }
}
