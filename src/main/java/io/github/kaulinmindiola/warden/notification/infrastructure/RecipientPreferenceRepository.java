package io.github.kaulinmindiola.warden.notification.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the language and the notification opt-outs a user has chosen. */
@Repository
public class RecipientPreferenceRepository {

    private final JdbcClient jdbc;

    public RecipientPreferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The language the user chose, if any. */
    public Optional<String> findLocale(UUID userId) {
        return jdbc.sql("SELECT locale FROM recipient_setting WHERE user_id = ?")
                .param(userId)
                .query(String.class)
                .optional();
    }

    /** Whether the user turned the notification off in the workspace; enabled unless stated. */
    public boolean isDisabled(UUID userId, String templateKey, UUID workspaceId) {
        return jdbc.sql("""
                        SELECT count(*) FROM notification_preference
                        WHERE user_id = ? AND template_key = ? AND workspace_id = ? AND NOT enabled
                        """)
                        .param(userId)
                        .param(templateKey)
                        .param(workspaceId)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
