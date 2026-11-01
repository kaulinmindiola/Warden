package io.github.kaulinmindiola.warden.notification.application;

import java.util.UUID;

/**
 * One requested preference: turn a notification on or off, in a workspace for workspace-scope
 * keys. Fields may be missing; the service validates them.
 */
public record PreferenceChange(String key, UUID workspaceId, Boolean enabled) {}
