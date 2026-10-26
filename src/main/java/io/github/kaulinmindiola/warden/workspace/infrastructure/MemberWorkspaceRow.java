package io.github.kaulinmindiola.warden.workspace.infrastructure;

import java.time.Instant;
import java.util.UUID;

/**
 * A workspace of a user, as read from the database.
 *
 * @param id workspace id
 * @param name workspace name
 * @param role the user's role as stored; may be an unknown value
 * @param createdAt when the workspace was created
 */
public record MemberWorkspaceRow(UUID id, String name, String role, Instant createdAt) {}
