package io.github.kaulinmindiola.warden.shared.outbox;

import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores domain events in {@code outbox_event} inside the caller's transaction (write side of
 * REQ-REL-001). Publishing without an active transaction fails, so an event always commits or
 * rolls back together with the state change it describes.
 */
@Component
public class OutboxPublisher {

    private static final String INSERT = """
            INSERT INTO outbox_event (id, type, schema_version, workspace_id, payload, trace_id, occurred_at)
            VALUES (:id, :type, :schemaVersion, :workspaceId, CAST(:payload AS jsonb), :traceId, :occurredAt)
            """;

    private final JdbcClient jdbc;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<Tracer> tracer;

    public OutboxPublisher(
            JdbcClient jdbc, UuidV7Generator ids, Clock clock, JsonMapper jsonMapper, ObjectProvider<Tracer> tracer) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer;
    }

    /**
     * Publishes an event with schema version 1.
     *
     * @param type {@code module.EventName}
     * @param workspaceId workspace of the event, or {@code null} for system scope
     * @param payload object serialized as the JSON payload
     * @return the stored envelope
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DomainEvent publish(String type, UUID workspaceId, Object payload) {
        return publish(type, 1, workspaceId, payload);
    }

    /**
     * Publishes an event with an explicit schema version.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DomainEvent publish(String type, int schemaVersion, UUID workspaceId, Object payload) {
        Objects.requireNonNull(payload, "payload");
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        DomainEvent event = new DomainEvent(
                ids.next(),
                type,
                schemaVersion,
                occurredAt,
                workspaceId,
                traceId(),
                jsonMapper.writeValueAsString(payload));

        jdbc.sql(INSERT)
                .param("id", event.eventId())
                .param("type", event.type())
                .param("schemaVersion", event.schemaVersion())
                .param("workspaceId", new SqlParameterValue(Types.OTHER, event.workspaceId()))
                .param("payload", event.payload())
                .param("traceId", event.traceId())
                .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .update();
        return event;
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
