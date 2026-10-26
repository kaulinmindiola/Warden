package io.github.kaulinmindiola.warden.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.kaulinmindiola.warden.shared.security.MembershipUnavailableException;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.security.WorkspaceAccess;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceMemberRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The fail-closed branches of the lookup that a real database cannot produce on demand.
 *
 * <p>Failures are stubbed with {@code doThrow(…).when(mock)}, which does not call the method:
 * {@code when(mock.method())} would call it and trigger a failure stubbed earlier.
 */
class WorkspaceMembershipLookupTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
    private final TenantTransactions tenantTransactions = mock(TenantTransactions.class);
    private final WorkspaceMembershipLookup lookup = new WorkspaceMembershipLookup(members, tenantTransactions);

    @Test
    void aKnownStoredRoleGrantsAccess() {
        runTenantTransactionsInline();
        when(members.findActiveRole(WORKSPACE, USER)).thenReturn(Optional.of("ADMIN"));

        assertThat(lookup.find(WORKSPACE, USER)).contains(new WorkspaceAccess(WORKSPACE, Role.ADMIN));
    }

    @Test
    @Verifies("REQ-SEC-011")
    void anUnknownStoredRoleGrantsNothing() {
        runTenantTransactionsInline();
        when(members.findActiveRole(WORKSPACE, USER)).thenReturn(Optional.of("SUPERUSER"));

        assertThat(lookup.find(WORKSPACE, USER)).isEmpty();
        assertThat(WorkspaceMembershipLookup.accessFrom(WORKSPACE, "owner")).isEmpty();
    }

    @Test
    void noMembershipGrantsNothing() {
        runTenantTransactionsInline();
        when(members.findActiveRole(WORKSPACE, USER)).thenReturn(Optional.empty());

        assertThat(lookup.find(WORKSPACE, USER)).isEmpty();
    }

    @Test
    @Verifies("REQ-SEC-011")
    void aTransactionThatCannotStartMakesMembershipsUnavailable() {
        doThrow(new CannotCreateTransactionException("database down"))
                .when(tenantTransactions)
                .forUser(any(), any());

        assertThatThrownBy(() -> lookup.find(WORKSPACE, USER)).isInstanceOf(MembershipUnavailableException.class);
        assertThatThrownBy(() -> lookup.workspaceIdsOf(USER)).isInstanceOf(MembershipUnavailableException.class);
    }

    @Test
    @Verifies("REQ-SEC-011")
    void aFailingQueryMakesMembershipsUnavailable() {
        doThrow(new DataAccessResourceFailureException("connection refused"))
                .when(tenantTransactions)
                .forUser(any(), any());

        assertThatThrownBy(() -> lookup.find(WORKSPACE, USER)).isInstanceOf(MembershipUnavailableException.class);
        assertThatThrownBy(() -> lookup.workspaceIdsOf(USER)).isInstanceOf(MembershipUnavailableException.class);
    }

    /** Makes the mocked tenant transactions run their work directly. */
    private void runTenantTransactionsInline() {
        when(tenantTransactions.forUser(any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
    }
}
