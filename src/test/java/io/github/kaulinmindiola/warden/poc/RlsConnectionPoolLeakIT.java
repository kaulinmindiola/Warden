package io.github.kaulinmindiola.warden.poc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POC-002 (REQ-POC-002): the tenant context of SDD V07 does not leak across pooled connections.
 *
 * <p>The context is a transaction-local GUC ({@code set_config('app.workspace_id', id, true)}) read
 * by a Row Level Security policy that uses {@code NULLIF(..., '')}. The table is forced to apply
 * RLS to its owner too. Kept as a permanent regression test: a driver, pool or framework upgrade
 * could reintroduce the leak.
 */
@Verifies("REQ-POC-002")
class RlsConnectionPoolLeakIT extends IntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("0190a0a0-0000-7000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0190b0b0-0000-7000-8000-00000000000b");
    private static final int ROWS_A = 3;
    private static final int ROWS_B = 2;
    private static final int TRANSACTIONS = 1_000;
    private static final int THREADS = 4;

    /** PostgreSQL SQLSTATE for insufficient_privilege, also raised by RLS WITH CHECK violations. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    private DataSource applicationDataSource;

    @Autowired
    private PlatformTransactionManager jpaTransactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void createForcedRlsTableAndSeedBothTenants() {
        JdbcClient owner = JdbcClient.create(ownerDataSource());
        owner.sql("DROP TABLE IF EXISTS poc_rls_item").update();
        owner.sql("CREATE TABLE poc_rls_item (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, label text NOT NULL)")
                .update();
        owner.sql("ALTER TABLE poc_rls_item ENABLE ROW LEVEL SECURITY").update();
        owner.sql("ALTER TABLE poc_rls_item FORCE ROW LEVEL SECURITY").update();
        owner.sql("""
                        CREATE POLICY poc_rls_item_tenant ON poc_rls_item
                          USING      (tenant_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
                          WITH CHECK (tenant_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
                        """).update();

        JdbcClient superuser = JdbcClient.create(superuserDataSource());
        for (int i = 0; i < ROWS_A; i++) {
            insertRow(superuser, TENANT_A, "a-" + i);
        }
        for (int i = 0; i < ROWS_B; i++) {
            insertRow(superuser, TENANT_B, "b-" + i);
        }
    }

    @AfterEach
    void dropTable() {
        JdbcClient.create(ownerDataSource())
                .sql("DROP TABLE IF EXISTS poc_rls_item")
                .update();
    }

    /** (a) 1 000 transactions alternating two tenants over a pool of 2 connections: zero cross reads. */
    @Test
    void alternatingTenantsOnAPoolOfTwoNeverReadEachOthersRows() throws Exception {
        Map<Integer, Set<UUID>> tenantsByBackend = new ConcurrentHashMap<>();
        AtomicInteger crossReads = new AtomicInteger();
        AtomicInteger wrongRowCounts = new AtomicInteger();

        try (HikariDataSource pool = newApplicationRolePool("poc-002-two", 2);
                ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            JdbcClient jdbc = JdbcClient.create(pool);
            TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));

            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < TRANSACTIONS; i++) {
                UUID tenant = (i % 2 == 0) ? TENANT_A : TENANT_B;
                int expectedRows = tenant.equals(TENANT_A) ? ROWS_A : ROWS_B;
                tasks.add(() -> {
                    transactions.executeWithoutResult(status -> {
                        setTenant(jdbc, tenant);
                        List<UUID> visibleTenants = visibleTenants(jdbc);
                        int backend = backendPid(jdbc);

                        tenantsByBackend
                                .computeIfAbsent(backend, pid -> ConcurrentHashMap.newKeySet())
                                .add(tenant);
                        if (visibleTenants.stream().anyMatch(seen -> !seen.equals(tenant))) {
                            crossReads.incrementAndGet();
                        }
                        if (visibleTenants.size() != expectedRows) {
                            wrongRowCounts.incrementAndGet();
                        }
                    });
                    return null;
                });
            }
            for (Future<Void> result : executor.invokeAll(tasks)) {
                result.get();
            }
        }

        System.out.println("POC-002 transactions: " + TRANSACTIONS + ", cross reads: " + crossReads.get()
                + ", wrong row counts: " + wrongRowCounts.get() + ", tenants served per backend: "
                + tenantsByBackend);
        assertThat(crossReads).hasValue(0);
        assertThat(wrongRowCounts).hasValue(0);
        assertThat(tenantsByBackend).hasSizeLessThanOrEqualTo(2);
        assertThat(tenantsByBackend.values())
                .as("at least one pooled connection must have served both tenants")
                .anySatisfy(tenants -> assertThat(tenants).containsExactlyInAnyOrder(TENANT_A, TENANT_B));
    }

    /** (b) A transaction without context sees no rows, even on the connection a tenant just used. */
    @Test
    void aTransactionWithoutContextSeesNoRowsEvenOnAReusedConnection() {
        try (HikariDataSource pool = newApplicationRolePool("poc-002-one", 1)) {
            JdbcClient jdbc = JdbcClient.create(pool);
            TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
            int[] backends = new int[2];
            String[] settingAfterReuse = new String[1];
            int[] rowsWithoutContext = new int[1];

            transactions.executeWithoutResult(status -> {
                setTenant(jdbc, TENANT_A);
                assertThat(visibleTenants(jdbc)).hasSize(ROWS_A);
                backends[0] = backendPid(jdbc);
            });
            transactions.executeWithoutResult(status -> {
                backends[1] = backendPid(jdbc);
                settingAfterReuse[0] = jdbc.sql("SELECT coalesce(current_setting('app.workspace_id', true), '<null>')")
                        .query(String.class)
                        .single();
                rowsWithoutContext[0] = visibleTenants(jdbc).size();
            });

            System.out.println("POC-002 reused connection: backend " + backends[0] + " -> " + backends[1]
                    + ", app.workspace_id after the tenant transaction: '" + settingAfterReuse[0] + "'");
            assertThat(backends[1]).as("same pooled connection").isEqualTo(backends[0]);
            assertThat(settingAfterReuse[0])
                    .as("a transaction-local setting reverts to an empty string, hence NULLIF in the policy")
                    .isEmpty();
            assertThat(rowsWithoutContext[0]).isZero();
        }
    }

    /** (c) JdbcClient inside a JPA transaction uses the same connection and sees the same context. */
    @Test
    void jdbcClientInsideAJpaTransactionSharesTheConnectionAndTheContext() {
        JdbcClient jdbc = JdbcClient.create(applicationDataSource);
        int[] backends = new int[2];
        List<UUID> visibleThroughJdbc = new ArrayList<>();

        new TransactionTemplate(jpaTransactionManager).executeWithoutResult(status -> {
            entityManager
                    .createNativeQuery("SELECT set_config('app.workspace_id', :tenant, true)")
                    .setParameter("tenant", TENANT_A.toString())
                    .getSingleResult();
            backends[0] = ((Number) entityManager
                            .createNativeQuery("SELECT pg_backend_pid()")
                            .getSingleResult())
                    .intValue();

            backends[1] = backendPid(jdbc);
            visibleThroughJdbc.addAll(visibleTenants(jdbc));
        });

        assertThat(backends[1])
                .as("JdbcClient joins the JPA transaction's connection")
                .isEqualTo(backends[0]);
        assertThat(visibleThroughJdbc).hasSize(ROWS_A).containsOnly(TENANT_A);
    }

    /** (d) Negative control: the superuser sees every row; the owner sees none because of FORCE. */
    @Test
    void onlyTheSuperuserBypassesTheForcedPolicy() {
        int seenBySuperuser = countRows(JdbcClient.create(superuserDataSource()));
        int seenByOwner = countRows(JdbcClient.create(ownerDataSource()));

        System.out.println("POC-002 rows without context: superuser " + seenBySuperuser + ", owner " + seenByOwner);
        assertThat(seenBySuperuser).isEqualTo(ROWS_A + ROWS_B);
        assertThat(seenByOwner)
                .as("FORCE ROW LEVEL SECURITY applies the policy to the owner")
                .isZero();
    }

    /** The WITH CHECK clause rejects writing a row of another tenant inside a tenant context. */
    @Test
    void withCheckRejectsWritingARowOfAnotherTenant() {
        try (HikariDataSource pool = newApplicationRolePool("poc-002-write", 1)) {
            JdbcClient jdbc = JdbcClient.create(pool);
            TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));

            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                        setTenant(jdbc, TENANT_A);
                        insertRow(jdbc, TENANT_B, "smuggled");
                    }))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .asInstanceOf(InstanceOfAssertFactories.type(SQLException.class))
                    .extracting(SQLException::getSQLState)
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
        assertThat(countRows(JdbcClient.create(superuserDataSource()))).isEqualTo(ROWS_A + ROWS_B);
    }

    private static void setTenant(JdbcClient jdbc, UUID tenant) {
        jdbc.sql("SELECT set_config('app.workspace_id', :tenant, true)")
                .param("tenant", tenant.toString())
                .query(String.class)
                .single();
    }

    private static List<UUID> visibleTenants(JdbcClient jdbc) {
        return jdbc.sql("SELECT tenant_id::text FROM poc_rls_item").query(String.class).list().stream()
                .map(UUID::fromString)
                .toList();
    }

    private static int backendPid(JdbcClient jdbc) {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private static int countRows(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(*) FROM poc_rls_item")
                .query(Integer.class)
                .single();
    }

    private static void insertRow(JdbcClient jdbc, UUID tenant, String label) {
        jdbc.sql("INSERT INTO poc_rls_item (id, tenant_id, label) VALUES (:id::uuid, :tenant::uuid, :label)")
                .param("id", UUID.randomUUID().toString())
                .param("tenant", tenant.toString())
                .param("label", label)
                .update();
    }
}
