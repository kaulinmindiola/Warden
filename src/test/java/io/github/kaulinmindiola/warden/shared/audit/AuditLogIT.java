package io.github.kaulinmindiola.warden.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Audit log against PostgreSQL, connected as the application role. */
class AuditLogIT extends IntegrationTest {

    /** PostgreSQL SQLSTATE for insufficient_privilege ("permission denied"). */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Verifies("REQ-SEC-016")
    void theApplicationRoleCannotModifyOrDeleteAuditRecords() {
        UUID actor = UUID.randomUUID();
        transaction()
                .executeWithoutResult(status -> auditLog.recordSuccess(AuditEntry.of(AuditAction.LOGIN, actor, null)));

        assertPermissionDenied("UPDATE audit_log SET outcome = 'FAILURE' WHERE actor_user_id = ?", actor);
        assertPermissionDenied("DELETE FROM audit_log WHERE actor_user_id = ?", actor);
        assertPermissionDenied("TRUNCATE audit_log");
        assertThat(outcomes(actor)).containsExactly("SUCCESS");
    }

    @Test
    void aCommittedSuccessRecordsEveryField() {
        Instant now = Instant.parse("2026-06-01T12:00:00.123456Z");
        clock.setInstant(now);
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();

        transaction()
                .executeWithoutResult(status -> auditLog.recordSuccess(
                        new AuditEntry(AuditAction.MEMBER_ROLE_CHANGED, actor, workspace, Map.of("newRole", "ADMIN"))));

        AuditRow row = jdbc().sql("""
                        SELECT id, occurred_at, workspace_id, action, outcome, details ->> 'newRole' AS new_role, trace_id
                        FROM audit_log WHERE actor_user_id = ?
                        """)
                .param(actor)
                .query((rs, rowNum) -> new AuditRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("outcome"),
                        rs.getString("new_role"),
                        rs.getString("trace_id")))
                .single();

        assertThat(row.id().version()).isEqualTo(7);
        assertThat(row.occurredAt()).isEqualTo(now);
        assertThat(row.workspaceId()).isEqualTo(workspace);
        assertThat(row.action()).isEqualTo("MEMBER_ROLE_CHANGED");
        assertThat(row.outcome()).isEqualTo("SUCCESS");
        assertThat(row.newRole()).isEqualTo("ADMIN");
        assertThat(row.traceId()).matches("[0-9a-f]{32}");
    }

    @Test
    void aSuccessRollsBackWithTheCallersTransaction() {
        UUID actor = UUID.randomUUID();

        transaction().executeWithoutResult(status -> {
            auditLog.recordSuccess(AuditEntry.of(AuditAction.WORKSPACE_CREATED, actor, UUID.randomUUID()));
            status.setRollbackOnly();
        });

        assertThat(outcomes(actor)).isEmpty();
    }

    @Test
    void aFailureSurvivesTheRollbackOfTheOuterTransaction() {
        UUID actor = UUID.randomUUID();

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                    auditLog.recordSuccess(AuditEntry.of(AuditAction.LOGOUT, actor, null));
                    auditLog.recordFailure(
                            new AuditEntry(AuditAction.LOGIN, actor, null, Map.of("reason", "wrong_password")));
                    throw new IllegalStateException("business failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("business failure");

        assertThat(outcomes(actor)).containsExactly("FAILURE");
    }

    @Test
    void aFailureIsRecordedWithoutAnOuterTransaction() {
        UUID actor = UUID.randomUUID();

        auditLog.recordFailure(AuditEntry.of(AuditAction.LOGIN, actor, null));

        assertThat(outcomes(actor)).containsExactly("FAILURE");
    }

    @Test
    void aSuccessOutsideATransactionIsRejected() {
        UUID actor = UUID.randomUUID();

        assertThatThrownBy(() -> auditLog.recordSuccess(AuditEntry.of(AuditAction.LOGIN, actor, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outcomes(actor)).isEmpty();
    }

    private void assertPermissionDenied(String sql, Object... params) {
        assertThatThrownBy(() -> jdbc().sql(sql).params(params).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .asInstanceOf(InstanceOfAssertFactories.type(SQLException.class))
                .extracting(SQLException::getSQLState)
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    private List<String> outcomes(UUID actor) {
        return jdbc().sql("SELECT outcome FROM audit_log WHERE actor_user_id = ?")
                .param(actor)
                .query(String.class)
                .list();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    private record AuditRow(
            UUID id,
            Instant occurredAt,
            UUID workspaceId,
            String action,
            String outcome,
            String newRole,
            String traceId) {}
}
