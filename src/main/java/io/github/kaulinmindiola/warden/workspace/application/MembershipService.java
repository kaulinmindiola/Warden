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
 * Changes to the members of a workspace (UC-16), following SDD V14-A4: every operation runs in
 * the workspace's tenant transaction and starts by locking the workspace row, so operations on
 * the members of one workspace are serialized and the last-OWNER guard cannot be raced (BR-001).
 * The actor's role is read again under the lock, never taken from earlier in the request.
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
            Workspace workspace =
                    workspaces.findByIdAndDeletedAtIsNull(workspaceId).orElseThrow(WorkspaceNotFoundException::new);
            Role actorRole = members.findByWorkspaceIdAndUserId(workspaceId, actorId)
                    .flatMap(WorkspaceMember::role)
                    .filter(role -> role.isAtLeast(Role.ADMIN))
                    .orElseThrow(InsufficientAuthorityException::new);
            WorkspaceMember target =
                    members.findByWorkspaceIdAndUserId(workspaceId, targetId).orElseThrow(MemberNotFoundException::new);
            Role previousRole = target.role().orElseThrow(MemberNotFoundException::new);

            if (actorRole == Role.ADMIN && (previousRole == Role.OWNER || newRole == Role.OWNER)) {
                throw new InsufficientAuthorityException();
            }
            if (previousRole == Role.OWNER && newRole != Role.OWNER && ownerCount(workspaceId) <= 1) {
                throw new LastOwnerException();
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

    private long ownerCount(UUID workspaceId) {
        return members.countByWorkspaceIdAndRole(workspaceId, Role.OWNER.name());
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
