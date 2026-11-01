package io.github.kaulinmindiola.warden.notification.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The language and the notification opt-outs a user has chosen (no Row Level Security). */
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

    /** The workspaces in which the user turned the notification off. */
    public Set<UUID> findDisabledWorkspaces(UUID userId, String templateKey) {
        return new HashSet<>(
                jdbc.sql("""
                        SELECT workspace_id FROM notification_preference
                        WHERE user_id = ? AND template_key = ? AND NOT enabled
                        """).param(userId).param(templateKey).query(UUID.class).list());
    }

    /** Sets the user's language, replacing any previous choice. */
    public void saveLocale(UUID userId, String locale, Instant now) {
        jdbc.sql("""
                        INSERT INTO recipient_setting (user_id, locale, updated_at) VALUES (?, ?, ?)
                        ON CONFLICT (user_id) DO UPDATE SET locale = EXCLUDED.locale, updated_at = EXCLUDED.updated_at
                        """)
                .param(userId)
                .param(locale)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /** Turns one notification on or off for the user in one workspace. */
    public void savePreference(UUID userId, String templateKey, UUID workspaceId, boolean enabled, Instant now) {
        jdbc.sql("""
                        INSERT INTO notification_preference (user_id, template_key, workspace_id, enabled, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, template_key, workspace_id)
                        DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at
                        """)
                .param(userId)
                .param(templateKey)
                .param(workspaceId)
                .param(enabled)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }
}
