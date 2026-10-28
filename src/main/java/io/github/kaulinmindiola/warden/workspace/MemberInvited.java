package io.github.kaulinmindiola.warden.workspace;

import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import io.github.kaulinmindiola.warden.shared.security.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code workspace.MemberInvited} (SDD V10): an e-mail address was invited to a
 * workspace. The recipient has no user id, because the address may have no account yet; the
 * single-use token travels sealed (ADR-011, REQ-SEC-005).
 *
 * @param invitationId the invitation, also used to cancel the delivery when it is revoked or
 *     replaced
 * @param workspaceName name of the workspace at the time of the invitation
 * @param role the role the invitation grants
 * @param recipient the invited address with its locale
 * @param secret the sealed token
 * @param expiresAt when the link expires
 */
public record MemberInvited(
        UUID invitationId,
        String workspaceName,
        Role role,
        Recipient recipient,
        SealedSecret secret,
        Instant expiresAt) {

    /** Event type stored in the outbox. */
    public static final String TYPE = "workspace.MemberInvited";
}
