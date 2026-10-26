package io.github.kaulinmindiola.warden.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SDD V07 layers L2 and L3: each kind of tenant transaction sets exactly its own
 * transaction-local setting, only {@code inWorkspace} enables the Hibernate filter, nothing
 * survives the transaction, and nesting is refused.
 */
class TenantTransactionsIT extends IntegrationTest {

    @Autowired
    private TenantTransactions tenantTransactions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Verifies("REQ-SEC-011")
    void inWorkspaceSetsTheWorkspaceAndEnablesTheTenantFilter() {
        UUID workspaceId = UUID.randomUUID();

        Context inside = tenantTransactions.inWorkspace(workspaceId, this::context);

        assertThat(inside).isEqualTo(new Context(workspaceId.toString(), "", "", true));
    }

    @Test
    @Verifies("REQ-SEC-011")
    void forUserSetsOnlyTheUser() {
        UUID userId = UUID.randomUUID();

        Context inside = tenantTransactions.forUser(userId, this::context);

        assertThat(inside).isEqualTo(new Context("", userId.toString(), "", false));
    }

    @Test
    void systemSetsOnlyTheSystemContext() {
        Context inside = tenantTransactions.system(this::context);

        assertThat(inside).isEqualTo(new Context("", "", "on", false));
    }

    @Test
    @Verifies("REQ-SEC-011")
    void theContextEndsWithItsTransaction() {
        tenantTransactions.inWorkspace(UUID.randomUUID(), this::context);

        Context after = new TransactionTemplate(transactionManager).execute(status -> context());

        assertThat(after).isEqualTo(new Context("", "", "", false));
    }

    @Test
    @Verifies("REQ-SEC-011")
    void aTenantTransactionCannotRunInsideAnotherTransaction() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(
                        status -> tenantTransactions.inWorkspace(UUID.randomUUID(), this::context)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside another transaction");
    }

    /** The tenant settings and the filter state seen inside the current transaction. */
    private Context context() {
        return new Context(
                setting(TenantTransactions.WORKSPACE_SETTING),
                setting(TenantTransactions.USER_SETTING),
                setting(TenantTransactions.SYSTEM_SETTING),
                entityManager.unwrap(Session.class).getEnabledFilter(TenantTransactions.TENANT_FILTER) != null);
    }

    /** A missing setting reads as NULL on a fresh connection and as '' after a reset; both mean unset. */
    private String setting(String name) {
        return jdbc.sql("SELECT coalesce(current_setting(?, true), '')")
                .param(name)
                .query(String.class)
                .single();
    }

    private record Context(String workspace, String user, String system, boolean tenantFilter) {}
}
