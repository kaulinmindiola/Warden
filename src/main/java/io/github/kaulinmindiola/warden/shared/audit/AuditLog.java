package io.github.kaulinmindiola.warden.shared.audit;

import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.sql.Types;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records security-relevant actions in the append-only {@code audit_log} table (REQ-SEC-015).
 *
 * <ul>
 *   <li>{@link #recordSuccess} joins the caller's transaction and requires one: the record
 *       commits or rolls back together with the business change.
 *   <li>{@link #recordFailure} runs in a new transaction: the record survives the rollback of the
 *       business transaction that failed.
 * </ul>
 */
@Component
public class AuditLog {

    private static final String INSERT = """
            INSERT INTO audit_log (id, occurred_at, actor_user_id, workspace_id, action, outcome, details, trace_id)
            VALUES (:id, :occurredAt, :actorUserId, :workspaceId, :action, :outcome, CAST(:details AS jsonb), :traceId)
            """;

    private final JdbcClient jdbc;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<Tracer> tracer;

    public AuditLog(
            JdbcClient jdbc, UuidV7Generator ids, Clock clock, JsonMapper jsonMapper, ObjectProvider<Tracer> tracer) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer;
    }

    /**
     * Records a successful action in the caller's transaction.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(AuditEntry entry) {
        insert(entry, AuditOutcome.SUCCESS);
    }

    /** Records a failed action in its own transaction, which commits even if the caller's rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(AuditEntry entry) {
        insert(entry, AuditOutcome.FAILURE);
    }

    private void insert(AuditEntry entry, AuditOutcome outcome) {
        jdbc.sql(INSERT)
                .param("id", ids.next())
                .param("occurredAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("actorUserId", new SqlParameterValue(Types.OTHER, entry.actorUserId()))
                .param("workspaceId", new SqlParameterValue(Types.OTHER, entry.workspaceId()))
                .param("action", entry.action().name())
                .param("outcome", outcome.name())
                .param("details", jsonMapper.writeValueAsString(new TreeMap<>(entry.details())))
                .param("traceId", traceId())
                .update();
    }

    private String traceId() {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        if (span != null) {
            return span.context().traceId();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
