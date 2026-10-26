package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.shared.security.MembershipLookup;
import io.github.kaulinmindiola.warden.shared.security.MembershipUnavailableException;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.security.WorkspaceAccess;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceMemberRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * {@link MembershipLookup} over {@code workspace_member} (SDD V09, V12). Reads on every call, never
 * caches (BR-004), and fails closed (REQ-SEC-011):
 *
 * <ul>
 *   <li>no membership or a deleted workspace (BR-012) → empty;
 *   <li>an unknown stored role → empty, logged as an error;
 *   <li>the database cannot be read → {@link MembershipUnavailableException}.
 * </ul>
 */
@Component
class WorkspaceMembershipLookup implements MembershipLookup {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMembershipLookup.class);

    private final WorkspaceMemberRepository members;
    private final TenantTransactions tenantTransactions;

    WorkspaceMembershipLookup(WorkspaceMemberRepository members, TenantTransactions tenantTransactions) {
        this.members = members;
        this.tenantTransactions = tenantTransactions;
    }

    @Override
    public Optional<WorkspaceAccess> find(UUID workspaceId, UUID userId) {
        Optional<String> storedRole =
                read(() -> tenantTransactions.forUser(userId, () -> members.findActiveRole(workspaceId, userId)));
        return storedRole.flatMap(role -> accessFrom(workspaceId, role));
    }

    @Override
    public List<UUID> workspaceIdsOf(UUID userId) {
        return read(() -> tenantTransactions.forUser(userId, () -> members.findActiveWorkspaceIds(userId)));
    }

    /** The access granted by a stored role; an unknown value grants nothing. */
    static Optional<WorkspaceAccess> accessFrom(UUID workspaceId, String storedRole) {
        Optional<Role> role = Role.fromStored(storedRole);
        if (role.isEmpty()) {
            log.error("Workspace {} has a membership with an unknown role; access is denied", workspaceId);
        }
        return role.map(known -> new WorkspaceAccess(workspaceId, known));
    }

    private static <T> T read(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException | TransactionException failure) {
            throw new MembershipUnavailableException("Workspace memberships could not be read", failure);
        }
    }
}
