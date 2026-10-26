package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.workspace.application.WorkspaceView;
import java.time.Instant;
import java.util.UUID;

/**
 * A workspace in API responses (SDD V08).
 *
 * @param id workspace id
 * @param name workspace name
 * @param role the caller's role in it
 * @param createdAt when it was created, in UTC
 */
public record WorkspaceResponse(UUID id, String name, Role role, Instant createdAt) {

    static WorkspaceResponse from(WorkspaceView view) {
        return new WorkspaceResponse(view.id(), view.name(), view.role(), view.createdAt());
    }
}
