package io.github.kaulinmindiola.warden.shared.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactions with a tenant context (SDD V07, layers L2 and L3).
 *
 * <ul>
 *   <li>{@link #inWorkspace}: sets {@code app.workspace_id} for Row Level Security and enables the
 *       Hibernate filter {@value #TENANT_FILTER} for tenant-scoped entities;
 *   <li>{@link #forUser}: sets {@code app.user_id}, for operations that span the user's own
 *       memberships;
 *   <li>{@link #forInvitationHolder}: sets {@code app.user_id} and the hash of a presented
 *       invitation token, for accepting an invitation before being a member;
 *   <li>{@link #system}: sets {@code app.system_context = 'on'}, reserved for background workers
 *       that need cross-tenant access (rule R5).
 * </ul>
 *
 * Settings are transaction-local ({@code set_config(…, true)}), so they never reach the next
 * transaction on the same pooled connection (POC-002). A tenant transaction cannot run inside
 * another transaction: its context would leak into the outer one.
 */
@Component
public class TenantTransactions {

    /** Name of the Hibernate filter defined in this package. */
    public static final String TENANT_FILTER = "tenant";

    /** Parameter of the {@value #TENANT_FILTER} filter. */
    public static final String WORKSPACE_PARAMETER = "workspaceId";

    static final String WORKSPACE_SETTING = "app.workspace_id";
    static final String USER_SETTING = "app.user_id";
    static final String SYSTEM_SETTING = "app.system_context";
    static final String INVITATION_SETTING = "app.invitation_token_hash";

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    public TenantTransactions(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    /** Runs {@code work} in a new transaction scoped to the workspace. */
    public <T> T inWorkspace(UUID workspaceId, Supplier<T> work) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return run(work, () -> {
            setLocal(WORKSPACE_SETTING, workspaceId.toString());
            entityManager
                    .unwrap(Session.class)
                    .enableFilter(TENANT_FILTER)
                    .setParameter(WORKSPACE_PARAMETER, workspaceId);
        });
    }

    /** Runs {@code work} in a new transaction scoped to the user's own rows. */
    public <T> T forUser(UUID userId, Supplier<T> work) {
        Objects.requireNonNull(userId, "userId");
        return run(work, () -> setLocal(USER_SETTING, userId.toString()));
    }

    /**
     * Runs {@code work} in a new transaction scoped to the user's own rows and to the one
     * invitation whose token hashes to {@code tokenHash}: Row Level Security lets the holder read
     * that invitation and mark it accepted, nothing else.
     */
    public <T> T forInvitationHolder(UUID userId, byte[] tokenHash, Supplier<T> work) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tokenHash, "tokenHash");
        String hexHash = HexFormat.of().formatHex(tokenHash);
        return run(work, () -> {
            setLocal(USER_SETTING, userId.toString());
            setLocal(INVITATION_SETTING, hexHash);
        });
    }

    /** Runs {@code work} in a new cross-tenant transaction. Only background workers may call it. */
    public <T> T system(Supplier<T> work) {
        return run(work, () -> setLocal(SYSTEM_SETTING, "on"));
    }

    private <T> T run(Supplier<T> work, Runnable establishContext) {
        Objects.requireNonNull(work, "work");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "A tenant transaction cannot run inside another transaction: its context would leak into it");
        }
        return transactions.execute(status -> {
            establishContext.run();
            return work.get();
        });
    }

    private void setLocal(String name, String value) {
        jdbc.sql("SELECT set_config(?, ?, true)")
                .param(name)
                .param(value)
                .query(String.class)
                .single();
    }
}
