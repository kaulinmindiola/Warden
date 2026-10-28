package io.github.kaulinmindiola.warden.workspace;

import java.util.Objects;
import java.util.UUID;

/**
 * Payload of {@code workspace.InvitationRevoked} (SDD V10): a pending invitation stopped being
 * valid. The notification module cancels its unsent delivery (REQ-FUNC-032, REQ-FUNC-035).
 *
 * @param invitationId the invitation
 * @param reason why it is no longer valid
 */
public record InvitationRevoked(UUID invitationId, Reason reason) {

    /** Event type stored in the outbox. */
    public static final String TYPE = "workspace.InvitationRevoked";

    /** Why an invitation stopped being valid. */
    public enum Reason {
        /** An ADMIN or OWNER revoked it. */
        REVOKED,
        /** A new invitation was sent to the same address. */
        REPLACED
    }

    public InvitationRevoked {
        Objects.requireNonNull(invitationId, "invitationId");
        Objects.requireNonNull(reason, "reason");
    }
}
