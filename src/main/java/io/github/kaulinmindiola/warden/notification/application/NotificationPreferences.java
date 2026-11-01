package io.github.kaulinmindiola.warden.notification.application;

import java.util.List;
import java.util.UUID;

/**
 * A user's notification settings (UC-26, REQ-FUNC-049 as reduced for release 1.0).
 *
 * @param locale the language the user chose here, or {@code null} when none was chosen and the
 *     language given at registration applies, {@code es} by default (REQ-FUNC-051)
 * @param preferences {@code workspace.role-changed} for every workspace the user belongs to,
 *     enabled unless turned off (REQ-FUNC-050)
 */
public record NotificationPreferences(String locale, List<Preference> preferences) {

    public NotificationPreferences {
        preferences = List.copyOf(preferences);
    }

    /** Whether one notification is enabled in one workspace. */
    public record Preference(String key, UUID workspaceId, boolean enabled) {}
}
