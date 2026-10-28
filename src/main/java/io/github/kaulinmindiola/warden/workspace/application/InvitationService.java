package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.iam.UserDirectory;
import io.github.kaulinmindiola.warden.iam.UserSummary;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.workspace.InvitationRevoked;
import io.github.kaulinmindiola.warden.workspace.MemberInvited;
import io.github.kaulinmindiola.warden.workspace.domain.Invitation;
import io.github.kaulinmindiola.warden.workspace.domain.InvitationStatus;
import io.github.kaulinmindiola.warden.workspace.domain.Workspace;
import io.github.kaulinmindiola.warden.workspace.domain.WorkspaceMember;
import io.github.kaulinmindiola.warden.workspace.infrastructure.InvitationRepository;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceMemberRepository;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Invitations of a workspace (UC-12, UC-13). Creating and revoking follow SDD V14-A4: the
 * workspace row is locked and the actor's role read again under the lock, which also serializes
 * re-invitations of the same address (V16). Only the hash of the token is stored; the token
 * travels sealed in {@code workspace.MemberInvited} (REQ-SEC-004, REQ-SEC-005).
 */
@Service
public class InvitationService {

    static final String REQUIRED = "is required";
    static final String MALFORMED_EMAIL = "must be a well-formed e-mail address";
    static final String INVITABLE_ROLE = "must be one of: ADMIN, MEMBER, VIEWER";
    static final String UNSUPPORTED_LOCALE = "must be one of: es, en";

    private static final Set<String> LOCALES = Set.of("es", "en");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final InvitationRepository invitations;
    private final UserDirectory users;
    private final TenantTransactions tenantTransactions;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final SecretCipher secretCipher;
    private final OutboxPublisher outbox;
    private final AuditLog auditLog;
    private final UuidV7Generator ids;
    private final Clock clock;

    public InvitationService(
            WorkspaceRepository workspaces,
            WorkspaceMemberRepository members,
            InvitationRepository invitations,
            UserDirectory users,
            TenantTransactions tenantTransactions,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            SecretCipher secretCipher,
            OutboxPublisher outbox,
            AuditLog auditLog,
            UuidV7Generator ids,
            Clock clock) {
        this.workspaces = workspaces;
        this.members = members;
        this.invitations = invitations;
        this.users = users;
        this.tenantTransactions = tenantTransactions;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.secretCipher = secretCipher;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Invites an address with ADMIN, MEMBER or VIEWER (REQ-FUNC-031). A pending invitation of the
     * same address, expired or not, is replaced (REQ-FUNC-032). Without a locale, the inviter's is
     * used.
     *
     * @throws InvalidInvitationException naming every invalid field
     * @throws WorkspaceNotFoundException if the workspace no longer exists
     * @throws InsufficientAuthorityException if the actor is no longer ADMIN or OWNER
     * @throws AlreadyMemberException if the address belongs to a member of the workspace
     */
    public InvitationView create(UUID workspaceId, UUID actorId, String rawEmail, String rawRole, String rawLocale) {
        Map<String, String> violations = new LinkedHashMap<>();
        Optional<String> email = Optional.empty();
        if (rawEmail == null) {
            violations.put("email", REQUIRED);
        } else {
            email = users.normalizeEmail(rawEmail);
            if (email.isEmpty()) {
                violations.put("email", MALFORMED_EMAIL);
            }
        }
        Optional<Role> role = Optional.empty();
        if (rawRole == null) {
            violations.put("role", REQUIRED);
        } else {
            role = Role.fromStored(rawRole).filter(candidate -> candidate != Role.OWNER);
            if (role.isEmpty()) {
                violations.put("role", INVITABLE_ROLE);
            }
        }
        if (rawLocale != null && !LOCALES.contains(rawLocale)) {
            violations.put("locale", UNSUPPORTED_LOCALE);
        }
        if (!violations.isEmpty()) {
            throw new InvalidInvitationException(violations);
        }

        String invitedEmail = email.orElseThrow();
        Role invitedRole = role.orElseThrow();
        UserSummary inviter = users.findById(actorId)
                .orElseThrow(() -> new IllegalStateException("The authenticated account does not exist"));
        String locale = rawLocale != null ? rawLocale : inviter.locale();
        Optional<UserSummary> invitedAccount = users.findByEmail(invitedEmail);
        String token = tokenGenerator.newOneTimeToken();
        byte[] tokenHash = tokenHasher.sha256(token);

        return tenantTransactions.inWorkspace(workspaceId, () -> {
            Workspace workspace = lockWorkspace(workspaceId);
            requireAdmin(workspaceId, actorId);
            if (invitedAccount.isPresent()
                    && members.findByWorkspaceIdAndUserId(
                                    workspaceId, invitedAccount.get().id())
                            .isPresent()) {
                throw new AlreadyMemberException();
            }

            for (Invitation previous : invitations.findByWorkspaceIdAndEmailAndStatus(
                    workspaceId, invitedEmail, InvitationStatus.PENDING)) {
                previous.replace();
                outbox.publish(
                        InvitationRevoked.TYPE,
                        workspaceId,
                        new InvitationRevoked(previous.id(), InvitationRevoked.Reason.REPLACED));
            }
            // Hibernate inserts before it updates: free the pending slot of the unique index first.
            invitations.flush();

            Invitation invitation = Invitation.issue(
                    ids.next(), workspaceId, invitedEmail, invitedRole, locale, tokenHash, actorId, clock.instant());
            invitations.persist(invitation);
            invitations.flush();

            SealedSecret secret = secretCipher.seal(token, invitation.expiresAt());
            outbox.publish(
                    MemberInvited.TYPE,
                    workspaceId,
                    new MemberInvited(
                            invitation.id(),
                            workspace.name(),
                            invitedRole,
                            new Recipient(null, invitedEmail, locale),
                            secret,
                            invitation.expiresAt()));
            auditLog.recordSuccess(new AuditEntry(
                    AuditAction.INVITATION_CREATED,
                    actorId,
                    workspaceId,
                    Map.of("invitationId", invitation.id().toString(), "role", invitedRole.name())));
            return InvitationView.from(invitation);
        });
    }

    /** The pending, unexpired invitations of the workspace, newest first (REQ-FUNC-034). */
    public Page<InvitationView> listPending(UUID workspaceId, Pageable pageable) {
        PageRequest ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
        return tenantTransactions.inWorkspace(
                workspaceId,
                () -> invitations
                        .findByWorkspaceIdAndStatusAndExpiresAtAfter(
                                workspaceId, InvitationStatus.PENDING, clock.instant(), ordered)
                        .map(InvitationView::from));
    }

    /**
     * Revokes a pending invitation (REQ-FUNC-035).
     *
     * @throws WorkspaceNotFoundException if the workspace no longer exists
     * @throws InsufficientAuthorityException if the actor is no longer ADMIN or OWNER
     * @throws InvitationNotFoundException if it does not exist in this workspace
     * @throws InvitationNotPendingException if it was accepted, revoked, replaced or has expired
     */
    public void revoke(UUID workspaceId, UUID actorId, UUID invitationId) {
        tenantTransactions.inWorkspace(workspaceId, () -> {
            lockWorkspace(workspaceId);
            requireAdmin(workspaceId, actorId);
            Invitation invitation = invitations
                    .findByIdAndWorkspaceId(invitationId, workspaceId)
                    .orElseThrow(InvitationNotFoundException::new);
            Instant now = clock.instant();
            if (!invitation.isPending(now)) {
                throw new InvitationNotPendingException();
            }
            invitation.revoke(now);
            outbox.publish(
                    InvitationRevoked.TYPE,
                    workspaceId,
                    new InvitationRevoked(invitation.id(), InvitationRevoked.Reason.REVOKED));
            auditLog.recordSuccess(new AuditEntry(
                    AuditAction.INVITATION_REVOKED,
                    actorId,
                    workspaceId,
                    Map.of("invitationId", invitation.id().toString())));
            return null;
        });
    }

    /** Locks the workspace row until the end of the transaction (SDD V14-A4). */
    private Workspace lockWorkspace(UUID workspaceId) {
        return workspaces.findByIdAndDeletedAtIsNull(workspaceId).orElseThrow(WorkspaceNotFoundException::new);
    }

    /** The actor's current role must be ADMIN or OWNER. */
    private void requireAdmin(UUID workspaceId, UUID actorId) {
        members.findByWorkspaceIdAndUserId(workspaceId, actorId)
                .flatMap(WorkspaceMember::role)
                .filter(current -> current.isAtLeast(Role.ADMIN))
                .orElseThrow(InsufficientAuthorityException::new);
    }
}
