package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.kaulinmindiola.warden.notification.domain.Template;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read access to {@code notification_template}, which the application role can only read. */
@Repository
public class NotificationTemplateRepository {

    private static final String FIND_GLOBAL = """
            SELECT template_key, locale, version, subject, body
            FROM notification_template
            WHERE scope = 'GLOBAL' AND template_key = :key AND locale IN (:locale, 'es')
            ORDER BY (locale = :locale) DESC, version DESC
            LIMIT 1
            """;

    private static final String FIND_GLOBAL_VERSION = """
            SELECT template_key, locale, version, subject, body
            FROM notification_template
            WHERE scope = 'GLOBAL' AND template_key = :key AND locale = :locale AND version = :version
            """;

    private static final String FIND_ALL_GLOBAL = """
            SELECT template_key, locale, version, subject, body
            FROM notification_template
            WHERE scope = 'GLOBAL'
            ORDER BY template_key, locale, version
            """;

    private final JdbcClient jdbc;

    public NotificationTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The latest global version of a key in the locale, or else in {@code es} (REQ-FUNC-052 as
     * reduced for release 1.0).
     */
    public Optional<Template> findGlobal(String key, String locale) {
        return jdbc.sql(FIND_GLOBAL)
                .param("key", key)
                .param("locale", locale)
                .query(NotificationTemplateRepository::toTemplate)
                .optional();
    }

    /** The global template a delivery pinned at creation (REQ-FUNC-053). */
    public Optional<Template> findGlobalVersion(String key, String locale, int version) {
        return jdbc.sql(FIND_GLOBAL_VERSION)
                .param("key", key)
                .param("locale", locale)
                .param("version", version)
                .query(NotificationTemplateRepository::toTemplate)
                .optional();
    }

    /** Every global template, every version, ordered by key, locale and version. */
    public List<Template> findAllGlobal() {
        return jdbc.sql(FIND_ALL_GLOBAL)
                .query(NotificationTemplateRepository::toTemplate)
                .list();
    }

    private static Template toTemplate(ResultSet row, int rowNumber) throws SQLException {
        return new Template(
                row.getString("template_key"),
                row.getString("locale"),
                row.getInt("version"),
                row.getString("subject"),
                row.getString("body"));
    }
}
