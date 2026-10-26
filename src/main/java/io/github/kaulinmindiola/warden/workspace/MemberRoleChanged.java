package io.github.kaulinmindiola.warden.workspace;

import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import io.github.kaulinmindiola.warden.shared.security.Role;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code workspace.MemberRoleChanged} (SDD V10): the role of a member changed. Carries
 * the affected member and every OWNER after the change, with their e-mail and locale, so the
 * notification module needs no other module (ADR-017, REQ-FUNC-040). An OWNER who is also the
 * affected member appears in both; consumers notify each recipient once.
 *
 * @param workspaceName name of the workspace at the time of the change
 * @param affected the member whose role changed
 * @param owners every OWNER of the workspace after the change
 * @param previousRole the role before the change
 * @param newRole the role after the change
 * @param changedBy the member who made the change
 */
public record MemberRoleChanged(
        String workspaceName,
        Recipient affected,
        List<Recipient> owners,
        Role previousRole,
        Role newRole,
        UUID changedBy) {

    /** Event type stored in the outbox. */
    public static final String TYPE = "workspace.MemberRoleChanged";

    public MemberRoleChanged {
        owners = List.copyOf(owners);
    }
}
