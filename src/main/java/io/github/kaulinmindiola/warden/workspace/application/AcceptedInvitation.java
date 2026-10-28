package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.shared.security.Role;
import java.util.UUID;

/**
 * The membership created by accepting an invitation.
 *
 * @param workspaceId the workspace joined
 * @param role the role granted by the invitation
 */
public record AcceptedInvitation(UUID workspaceId, Role role) {}
