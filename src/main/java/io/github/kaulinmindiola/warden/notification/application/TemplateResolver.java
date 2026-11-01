package io.github.kaulinmindiola.warden.notification.application;

import io.github.kaulinmindiola.warden.notification.domain.Template;
import io.github.kaulinmindiola.warden.notification.domain.TemplateKey;
import io.github.kaulinmindiola.warden.notification.infrastructure.NotificationTemplateRepository;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Chooses the template a delivery is rendered with (REQ-FUNC-052, reduced for release 1.0 by
 * D-01): the global template of the key in the recipient's locale, or else the global template in
 * {@code es}. Workspace customizations arrive with release 1.1.
 */
@Service
public class TemplateResolver {

    private static final Set<String> LOCALES = Set.of("es", "en");

    private final NotificationTemplateRepository templates;

    public TemplateResolver(NotificationTemplateRepository templates) {
        this.templates = templates;
    }

    /**
     * @param locale {@code es} or {@code en}
     * @throws IllegalArgumentException for any other locale
     * @throws IllegalStateException if the key has no global template, which the seed migration
     *     rules out
     */
    public Template resolve(TemplateKey key, String locale) {
        Objects.requireNonNull(key, "key");
        if (!LOCALES.contains(locale)) {
            throw new IllegalArgumentException("Unsupported locale: " + locale);
        }
        return templates
                .findGlobal(key.key(), locale)
                .orElseThrow(() -> new IllegalStateException("No global template for " + key.key()));
    }
}
