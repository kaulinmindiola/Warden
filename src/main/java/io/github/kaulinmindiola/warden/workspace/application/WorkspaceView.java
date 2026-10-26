package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.shared.security.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * A workspace as seen by one of its members.
 *
 * @param id workspace id
 * @param name workspace name
 * @param role the member's role
 * @param createdAt when the workspace was created
 */
public record WorkspaceView(UUID id, String name, Role role, Instant createdAt) {}
