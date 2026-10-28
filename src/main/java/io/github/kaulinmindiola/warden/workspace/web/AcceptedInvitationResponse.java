package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.workspace.application.AcceptedInvitation;
import java.util.UUID;

/** Response of an accepted invitation (SDD V08). */
public record AcceptedInvitationResponse(UUID workspaceId, Role role) {

    static AcceptedInvitationResponse from(AcceptedInvitation accepted) {
        return new AcceptedInvitationResponse(accepted.workspaceId(), accepted.role());
    }
}
