package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.iam.UserDirectory;
import io.github.kaulinmindiola.warden.iam.UserSummary;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.workspace.MemberRoleChanged;
import io.github.kaulinmindiola.warden.workspace.domain.Workspace;
import io.github.kaulinmindiola.warden.workspace.domain.WorkspaceMember;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceMemberRepository;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Changes to the members of a workspace (UC-16 to UC-18), following SDD V14-A4: every operation
 * runs in the workspace's tenant transaction and starts by locking the workspace row, so
 * operations on the members of one workspace are serialized and the last-OWNER guard cannot be
 * raced (BR-001, BR-011). The actor's role is read again under the lock, never taken from earlier
 * in the request.
 */
@Service
public class MembershipService {

    static final String REQUIRED = "is required";
    static final String UNKNOWN_ROLE = "must be one of: VIEWER, MEMBER, ADMIN, OWNER";

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final UserDirectory users;
    private final TenantTransactions tenantTransactions;
    private final OutboxPublisher outbox;
    private final AuditLog auditLog;

    public MembershipService(
            WorkspaceRepository workspaces,
            WorkspaceMemberRepository members,
            UserDirectory users,
            TenantTransactions tenantTransactions,
            OutboxPublisher outbox,
            AuditLog auditLog) {
        this.workspaces = workspaces;
        this.members = members;
        this.users = users;
        this.tenantTransactions = tenantTransactions;
        this.outbox = outbox;
        this.auditLog = auditLog;
    }

    /**
     * Changes the role of a member (REQ-FUNC-038). An OWNER may assign any role to anyone; an ADMIN
     * may assign ADMIN, MEMBER or VIEWER to members who are not OWNERs (BR-002, BR-008). Assigning
     * the current role changes nothing and publishes nothing.
     *
     * @throws InvalidMembershipException if the role is missing or unknown
     * @throws WorkspaceNotFoundException if the workspace no longer exists
     * @throws InsufficientAuthorityException if the actor may not make this change
     * @throws MemberNotFoundException if the target is not a member of this workspace
     * @throws LastOwnerException if the change would leave the workspace without an OWNER
     */
    public MemberView changeRole(UUID workspaceId, UUID actorId, UUID targetId, String rawRole) {
        Role newRole = parseRole(rawRole);
        return tenantTransactions.inWorkspace(workspaceId, () -> {
            Workspace workspace = lockWorkspace(workspaceId);
            Role actorRole = adminRoleOf(workspaceId, actorId);
            WorkspaceMember target = memberOf(workspaceId, targetId);
            Role previousRole = target.role().orElseThrow(MemberNotFoundException::new);

            if (actorRole == Role.ADMIN && (previousRole == Role.OWNER || newRole == Role.OWNER)) {
                throw new InsufficientAuthorityException();
            }
            if (previousRole == Role.OWNER && newRole != Role.OWNER) {
                requireAnotherOwner(workspaceId);
            }
            if (previousRole == newRole) {
                UserSummary unchanged = users.findById(targetId).orElseThrow(MemberNotFoundException::new);
                return new MemberView(targetId, unchanged.email(), newRole, target.joinedAt());
            }

            target.changeRole(newRole);
            members.flush();
            List<WorkspaceMember> owners =
                    members.findByWorkspaceIdAndRoleOrderByJoinedAtAscUserIdAsc(workspaceId, Role.OWNER.name());
            Set<UUID> recipientIds = new HashSet<>();
            recipientIds.add(targetId);
            owners.forEach(owner -> recipientIds.add(owner.userId()));
            Map<UUID, UserSummary> summaries = users.findByIds(recipientIds);

            outbox.publish(
                    MemberRoleChanged.TYPE,
                    workspaceId,
                    new MemberRoleChanged(
                            workspace.name(),
                            recipientOf(summaries, targetId),
                            owners.stream()
                                    .map(owner -> recipientOf(summaries, owner.userId()))
                                    .toList(),
                            previousRole,
                            newRole,
                            actorId));
            auditLog.recordSuccess(new AuditEntry(
                    AuditAction.MEMBER_ROLE_CHANGED,
                    actorId,
                    workspaceId,
                    Map.of(
                            "targetUserId", targetId.toString(),
                            "previousRole", previousRole.name(),
                            "newRole", newRole.name())));
            return new MemberView(targetId, summaries.get(targetId).email(), newRole, target.joinedAt());
        });
    }

    /**
     * Removes a member (REQ-FUNC-041). An OWNER may remove anyone; an ADMIN only members who are
     * not OWNERs (BR-002). The member's notes are kept.
     *
     * @throws WorkspaceNotFoundException if the workspace no longer exists
     * @throws InsufficientAuthorityException if the actor may not remove this member
     * @throws MemberNotFoundException if the target is not a member of this workspace
     * @throws LastOwnerException if the target is the last OWNER
     */
    public void removeMember(UUID workspaceId, UUID actorId, UUID targetId) {
        tenantTransactions.inWorkspace(workspaceId, () -> {
            lockWorkspace(workspaceId);
            Role actorRole = adminRoleOf(workspaceId, actorId);
            Role targetRole = memberOf(workspaceId, targetId).role().orElseThrow(MemberNotFoundException::new);

            if (actorRole == Role.ADMIN && targetRole == Role.OWNER) {
                throw new InsufficientAuthorityException();
            }
            if (targetRole == Role.OWNER) {
                requireAnotherOwner(workspaceId);
            }
            members.deleteMembership(workspaceId, targetId);
            auditLog.recordSuccess(new AuditEntry(
                    AuditAction.MEMBER_REMOVED,
                    actorId,
                    workspaceId,
                    Map.of("targetUserId", targetId.toString(), "role", targetRole.name())));
            return null;
        });
    }

    /**
     * The caller leaves the workspace (REQ-FUNC-042). Their notes are kept.
     *
     * @throws WorkspaceNotFoundException if the workspace no longer exists
     * @throws MemberNotFoundException if the caller is no longer a member
     * @throws LastOwnerException if the caller is the last OWNER
     */
    public void leave(UUID workspaceId, UUID userId) {
        tenantTransactions.inWorkspace(workspaceId, () -> {
            lockWorkspace(workspaceId);
            Role role = memberOf(workspaceId, userId).role().orElseThrow(MemberNotFoundException::new);
            if (role == Role.OWNER) {
                requireAnotherOwner(workspaceId);
            }
            members.deleteMembership(workspaceId, userId);
            auditLog.recordSuccess(
                    new AuditEntry(AuditAction.MEMBER_LEFT, userId, workspaceId, Map.of("role", role.name())));
            return null;
        });
    }

    /** Locks the workspace row until the end of the transaction (SDD V14-A4). */
    private Workspace lockWorkspace(UUID workspaceId) {
        return workspaces.findByIdAndDeletedAtIsNull(workspaceId).orElseThrow(WorkspaceNotFoundException::new);
    }

    /** The actor's current role, which must be ADMIN or OWNER. */
    private Role adminRoleOf(UUID workspaceId, UUID actorId) {
        return members.findByWorkspaceIdAndUserId(workspaceId, actorId)
                .flatMap(WorkspaceMember::role)
                .filter(role -> role.isAtLeast(Role.ADMIN))
                .orElseThrow(InsufficientAuthorityException::new);
    }

    private WorkspaceMember memberOf(UUID workspaceId, UUID userId) {
        return members.findByWorkspaceIdAndUserId(workspaceId, userId).orElseThrow(MemberNotFoundException::new);
    }

    /** Fails if the workspace has a single OWNER (BR-001, BR-011). */
    private void requireAnotherOwner(UUID workspaceId) {
        if (members.countByWorkspaceIdAndRole(workspaceId, Role.OWNER.name()) <= 1) {
            throw new LastOwnerException();
        }
    }

    private static Recipient recipientOf(Map<UUID, UserSummary> summaries, UUID userId) {
        UserSummary summary = summaries.get(userId);
        if (summary == null) {
            throw new IllegalStateException("A member has no account");
        }
        return new Recipient(summary.id(), summary.email(), summary.locale());
    }

    private static Role parseRole(String rawRole) {
        if (rawRole == null) {
            throw new InvalidMembershipException(Map.of("role", REQUIRED));
        }
        return Role.fromStored(rawRole).orElseThrow(() -> new InvalidMembershipException(Map.of("role", UNKNOWN_ROLE)));
    }
}
