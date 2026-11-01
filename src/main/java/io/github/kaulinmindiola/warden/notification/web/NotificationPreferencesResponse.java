package io.github.kaulinmindiola.warden.notification.web;

import io.github.kaulinmindiola.warden.notification.application.NotificationPreferences;
import java.util.List;
import java.util.UUID;

/**
 * The caller's notification settings.
 *
 * @param locale the language chosen in the preferences, or {@code null} when the language given at
 *     registration applies
 * @param preferences the optional notifications of every workspace the caller belongs to
 */
public record NotificationPreferencesResponse(String locale, List<Item> preferences) {

    /** Whether one notification is enabled in one workspace. */
    public record Item(String key, UUID workspaceId, boolean enabled) {}

    static NotificationPreferencesResponse from(NotificationPreferences settings) {
        return new NotificationPreferencesResponse(
                settings.locale(),
                settings.preferences().stream()
                        .map(preference -> new Item(preference.key(), preference.workspaceId(), preference.enabled()))
                        .toList());
    }
}
