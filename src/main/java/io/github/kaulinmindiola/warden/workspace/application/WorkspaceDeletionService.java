package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.workspace.WorkspaceDeleted;
import io.github.kaulinmindiola.warden.workspace.domain.Workspace;
import io.github.kaulinmindiola.warden.workspace.domain.WorkspaceMember;
import io.github.kaulinmindiola.warden.workspace.infrastructure.InvitationRepository;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceMemberRepository;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Deletion of a workspace (UC-19). Follows SDD V14-A4: the workspace row is locked and the
 * actor's role read again under the lock, so deleting is serialized with invitations, acceptances
 * and membership changes of the same workspace. Deleting is logical and irreversible
 * (REQ-FUNC-029); its pending invitations are revoked (REQ-FUNC-030) and {@code
 * workspace.WorkspaceDeleted} lets the notification module cancel unsent deliveries.
 */
@Service
public class WorkspaceDeletionService {

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final InvitationRepository invitations;
    private final TenantTransactions tenantTransactions;
    private final OutboxPublisher outbox;
    private final AuditLog auditLog;
    private final Clock clock;

    public WorkspaceDeletionService(
            WorkspaceRepository workspaces,
            WorkspaceMemberRepository members,
            InvitationRepository invitations,
            TenantTransactions tenantTransactions,
            OutboxPublisher outbox,
            AuditLog auditLog,
            Clock clock) {
        this.workspaces = workspaces;
        this.members = members;
        this.invitations = invitations;
        this.tenantTransactions = tenantTransactions;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * Deletes the workspace.
     *
     * @throws WorkspaceNotFoundException if it was already deleted
     * @throws InsufficientAuthorityException if the actor is no longer an OWNER
     */
    public void delete(UUID workspaceId, UUID actorId) {
        tenantTransactions.inWorkspace(workspaceId, () -> {
            Workspace workspace =
                    workspaces.findByIdAndDeletedAtIsNull(workspaceId).orElseThrow(WorkspaceNotFoundException::new);
            members.findByWorkspaceIdAndUserId(workspaceId, actorId)
                    .flatMap(WorkspaceMember::role)
                    .filter(role -> role == Role.OWNER)
                    .orElseThrow(InsufficientAuthorityException::new);

            workspace.delete(clock.instant());
            int revoked = invitations.revokePending(workspaceId);
            outbox.publish(WorkspaceDeleted.TYPE, workspaceId, WorkspaceDeleted.PAYLOAD);
            auditLog.recordSuccess(new AuditEntry(
                    AuditAction.WORKSPACE_DELETED,
                    actorId,
                    workspaceId,
                    Map.of("revokedInvitations", Integer.toString(revoked))));
            return null;
        });
    }
}
