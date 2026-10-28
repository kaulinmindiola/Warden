package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.workspace.domain.Invitation;
import java.time.Instant;
import java.util.UUID;

/**
 * An invitation as shown to ADMINs and OWNERs; never carries the token or its hash
 * (REQ-FUNC-034).
 *
 * @param id invitation id
 * @param email the invited address, normalized
 * @param role the role it grants
 * @param expiresAt when the link expires
 */
public record InvitationView(UUID id, String email, Role role, Instant expiresAt) {

    static InvitationView from(Invitation invitation) {
        return new InvitationView(invitation.id(), invitation.email(), invitation.role(), invitation.expiresAt());
    }
}
