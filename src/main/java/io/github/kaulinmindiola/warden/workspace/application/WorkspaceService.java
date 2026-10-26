package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.iam.UserDirectory;
import io.github.kaulinmindiola.warden.iam.UserSummary;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.workspace.domain.InvalidWorkspaceNameException;
import io.github.kaulinmindiola.warden.workspace.domain.Workspace;
import io.github.kaulinmindiola.warden.workspace.domain.WorkspaceMember;
import io.github.kaulinmindiola.warden.workspace.domain.WorkspaceName;
import io.github.kaulinmindiola.warden.workspace.infrastructure.MemberWorkspaceRow;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceMemberRepository;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Creating a workspace (UC-10) and listing the caller's workspaces (UC-11).
 *
 * <p>Both run in a transaction scoped to the caller, which Row Level Security on {@code
 * workspace_member} requires to read and insert the caller's own memberships (SDD V07).
 */
@Service
public class WorkspaceService {

    static final String REQUIRED = "is required";
    static final String NAME_LENGTH = "must be between 1 and " + WorkspaceName.MAX_LENGTH + " characters";

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final UserDirectory users;
    private final TenantTransactions tenantTransactions;
    private final AuditLog auditLog;
    private final UuidV7Generator ids;
    private final Clock clock;

    public WorkspaceService(
            WorkspaceRepository workspaces,
            WorkspaceMemberRepository members,
            UserDirectory users,
            TenantTransactions tenantTransactions,
            AuditLog auditLog,
            UuidV7Generator ids,
            Clock clock) {
        this.workspaces = workspaces;
        this.members = members;
        this.users = users;
        this.tenantTransactions = tenantTransactions;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Creates a workspace whose only member is its creator, as OWNER (REQ-FUNC-027, BR-001).
     *
     * @throws InvalidWorkspaceException if the name is missing, blank or longer than 100 characters
     * @throws EmailNotVerifiedException if the creator's e-mail is not verified (BR-010); nothing is
     *     created
     */
    public WorkspaceView create(UUID userId, String rawName) {
        WorkspaceName name = parseName(rawName);
        UserSummary creator = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("The authenticated account does not exist"));
        if (!creator.emailVerified()) {
            throw new EmailNotVerifiedException();
        }
        return tenantTransactions.forUser(userId, () -> {
            Instant now = clock.instant();
            Workspace workspace = Workspace.create(ids.next(), name, userId, now);
            workspaces.persist(workspace);
            members.persist(WorkspaceMember.join(workspace.id(), userId, Role.OWNER, now));
            auditLog.recordSuccess(AuditEntry.of(AuditAction.WORKSPACE_CREATED, userId, workspace.id()));
            return new WorkspaceView(workspace.id(), workspace.name(), Role.OWNER, workspace.createdAt());
        });
    }

    /**
     * The caller's non-deleted workspaces with the caller's role, oldest first (REQ-FUNC-028). A
     * membership with an unknown stored role is left out and logged.
     *
     * @param pageable an unsorted page request
     */
    public Page<WorkspaceView> listMine(UUID userId, Pageable pageable) {
        return tenantTransactions.forUser(userId, () -> {
            Page<MemberWorkspaceRow> rows = members.findWorkspacesOf(userId, pageable);
            List<WorkspaceView> views = rows.getContent().stream()
                    .flatMap(row -> toView(row).stream())
                    .toList();
            return new PageImpl<>(views, pageable, rows.getTotalElements());
        });
    }

    private static Optional<WorkspaceView> toView(MemberWorkspaceRow row) {
        Optional<Role> role = Role.fromStored(row.role());
        if (role.isEmpty()) {
            log.error("Workspace {} has a membership with an unknown role; it is not listed", row.id());
        }
        return role.map(known -> new WorkspaceView(row.id(), row.name(), known, row.createdAt()));
    }

    private static WorkspaceName parseName(String rawName) {
        if (rawName == null) {
            throw new InvalidWorkspaceException(Map.of("name", REQUIRED));
        }
        try {
            return WorkspaceName.of(rawName);
        } catch (InvalidWorkspaceNameException invalid) {
            throw new InvalidWorkspaceException(Map.of("name", NAME_LENGTH));
        }
    }
}
