package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.iam.UserDirectory;
import io.github.kaulinmindiola.warden.iam.UserSummary;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.workspace.InvitationAccepted;
import io.github.kaulinmindiola.warden.workspace.domain.Invitation;
import io.github.kaulinmindiola.warden.workspace.domain.WorkspaceMember;
import io.github.kaulinmindiola.warden.workspace.infrastructure.InvitationRepository;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceMemberRepository;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Acceptance of invitations (UC-14, REQ-FUNC-036, BR-009). The caller is not a member yet, so the
 * transaction is scoped to the caller and to the hash of the presented token: Row Level Security
 * exposes that invitation and nothing else (SDD V07). The workspace row is locked as in V14-A4,
 * which serializes acceptance with revocation and re-invitation, and the invitation is consumed by
 * a conditional update that only a pending, unexpired invitation passes. Rejections for the wrong
 * or an unverified e-mail leave the token usable.
 */
@Service
public class InvitationAcceptanceService {

    private final InvitationRepository invitations;
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final UserDirectory users;
    private final TenantTransactions tenantTransactions;
    private final TokenHasher tokenHasher;
    private final OutboxPublisher outbox;
    private final AuditLog auditLog;
    private final Clock clock;

    public InvitationAcceptanceService(
            InvitationRepository invitations,
            WorkspaceRepository workspaces,
            WorkspaceMemberRepository members,
            UserDirectory users,
            TenantTransactions tenantTransactions,
            TokenHasher tokenHasher,
            OutboxPublisher outbox,
            AuditLog auditLog,
            Clock clock) {
        this.invitations = invitations;
        this.workspaces = workspaces;
        this.members = members;
        this.users = users;
        this.tenantTransactions = tenantTransactions;
        this.tokenHasher = tokenHasher;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * Joins the workspace of the invitation with its role and consumes the token.
     *
     * @throws InvalidInvitationTokenException if the token is malformed, unknown, used, expired,
     *     revoked or replaced, or the workspace was deleted
     * @throws EmailNotVerifiedException if the caller's e-mail is not verified; token not consumed
     * @throws InvitationEmailMismatchException if the caller's e-mail is not the invited one; token
     *     not consumed
     * @throws AlreadyMemberException if the caller already belongs to the workspace; nothing changes
     */
    public AcceptedInvitation accept(UUID userId, String token) {
        if (token == null || !token.startsWith(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)) {
            throw new InvalidInvitationTokenException();
        }
        UserSummary user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("The authenticated account does not exist"));
        byte[] tokenHash = tokenHasher.sha256(token);

        return tenantTransactions.forInvitationHolder(userId, tokenHash, () -> {
            Instant now = clock.instant();
            Invitation invitation = invitations
                    .findByTokenHash(tokenHash)
                    .filter(candidate -> candidate.isPending(now))
                    .orElseThrow(InvalidInvitationTokenException::new);
            UUID workspaceId = invitation.workspaceId();
            workspaces.findByIdAndDeletedAtIsNull(workspaceId).orElseThrow(InvalidInvitationTokenException::new);

            if (!user.emailVerified()) {
                recordRejection(userId, invitation, "email-not-verified");
                throw new EmailNotVerifiedException();
            }
            if (!user.email().equals(invitation.email())) {
                recordRejection(userId, invitation, "email-mismatch");
                throw new InvitationEmailMismatchException();
            }
            if (members.findByWorkspaceIdAndUserId(workspaceId, userId).isPresent()) {
                throw new AlreadyMemberException();
            }
            if (invitations.markAccepted(invitation.id(), now) == 0) {
                throw new InvalidInvitationTokenException();
            }

            Role role = invitation.role();
            members.persist(WorkspaceMember.join(workspaceId, userId, role, now));
            outbox.publish(InvitationAccepted.TYPE, workspaceId, new InvitationAccepted(invitation.id()));
            auditLog.recordSuccess(new AuditEntry(
                    AuditAction.INVITATION_ACCEPTED,
                    userId,
                    workspaceId,
                    Map.of("invitationId", invitation.id().toString(), "role", role.name())));
            return new AcceptedInvitation(workspaceId, role);
        });
    }

    /** Records a refused acceptance in its own transaction; never the e-mail addresses. */
    private void recordRejection(UUID userId, Invitation invitation, String reason) {
        auditLog.recordFailure(new AuditEntry(
                AuditAction.INVITATION_ACCEPTED,
                userId,
                invitation.workspaceId(),
                Map.of("invitationId", invitation.id().toString(), "reason", reason)));
    }
}
