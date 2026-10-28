package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.workspace.application.InvitationView;
import java.time.Instant;
import java.util.UUID;

/** An invitation in API responses (SDD V08); never contains the token or its hash. */
public record InvitationResponse(UUID id, String email, Role role, Instant expiresAt) {

    static InvitationResponse from(InvitationView view) {
        return new InvitationResponse(view.id(), view.email(), view.role(), view.expiresAt());
    }
}
