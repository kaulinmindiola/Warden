package io.github.kaulinmindiola.warden.notification.web;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code PUT /api/v1/me/notification-preferences}. Both fields are optional; validation
 * happens in the service so every invalid field is reported together.
 *
 * @param locale {@code es} or {@code en}; omitted to keep the current language
 * @param preferences the preferences to set; omitted to change none
 */
public record UpdateNotificationPreferencesRequest(String locale, List<Item> preferences) {

    /** One preference: a notification key, the workspace for workspace-scope keys, and its state. */
    public record Item(String key, UUID workspaceId, Boolean enabled) {}
}
