package io.github.kaulinmindiola.warden.workspace;

import java.util.Objects;
import java.util.UUID;

/**
 * Payload of {@code workspace.InvitationAccepted} (SDD V10): an invitation was used to join its
 * workspace. The notification module stops treating its delivery as reprocessable (REQ-FUNC-060).
 *
 * @param invitationId the invitation
 */
public record InvitationAccepted(UUID invitationId) {

    /** Event type stored in the outbox. */
    public static final String TYPE = "workspace.InvitationAccepted";

    public InvitationAccepted {
        Objects.requireNonNull(invitationId, "invitationId");
    }
}
