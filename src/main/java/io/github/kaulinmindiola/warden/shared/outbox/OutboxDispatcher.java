package io.github.kaulinmindiola.warden.shared.outbox;

import io.github.kaulinmindiola.warden.shared.logging.LogFields;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Read side of the transactional outbox (REQ-REL-001, SDD V13, ADR-008): hands pending events to
 * every {@link OutboxEventHandler} and marks them published.
 *
 * <p>One batch runs in the caller's transaction. The pending events are taken in insertion order
 * ({@code dispatch_order}, V17), so an event is handed over after those written before it, such
 * as a cancellation after the invitation it cancels. They are locked with {@code FOR UPDATE SKIP
 * LOCKED}, so concurrent dispatchers, in this or another instance, take disjoint batches.
 *
 * <p>Each event is handed over under a savepoint; once every handler has consumed it, the event is
 * marked published and its sealed secret is removed from the payload, in the same transaction that
 * created whatever the handlers derived from it (REQ-SEC-005). A crash before the commit leaves the
 * batch pending for the next dispatcher.
 *
 * <p>The dispatcher does not open the transaction: the notification worker does, in the system
 * context its handler needs (SDD V04 R5, V07).
 *
 * <p>The time from an event's occurrence to its publication is recorded in {@code
 * warden_outbox_lag_seconds}, a histogram with a bucket at the 5 s target of REQ-PERF-004
 * (REQ-OBS-002). Every log line about an event, those of its handlers included, carries {@code
 * originTraceId}, the trace of the request that stored it (REQ-OBS-006).
 */
@Component
public class OutboxDispatcher {

    /** Largest number of events handed over in one transaction. */
    public static final int BATCH_SIZE = 100;

    static final String FAILURE_METRIC = "warden.outbox.dispatch.failures";

    /** Time from an event's occurrence to its publication. */
    public static final String LAG_METRIC = "warden.outbox.lag";

    /** The p95 target of the outbox lag (REQ-PERF-004). */
    public static final Duration LAG_TARGET = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private static final String SELECT_PENDING = """
            SELECT id, type, schema_version, workspace_id, payload::text AS payload, trace_id, occurred_at
            FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY dispatch_order
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_PUBLISHED = """
            UPDATE outbox_event
            SET published_at = :publishedAt, payload = payload - 'secret'
            WHERE id = :id
            """;

    private final JdbcClient jdbc;
    private final ObjectProvider<OutboxEventHandler> handlers;
    private final Clock clock;
    private final Counter failures;
    private final Timer lag;

    public OutboxDispatcher(
            JdbcClient jdbc, ObjectProvider<OutboxEventHandler> handlers, Clock clock, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.handlers = handlers;
        this.clock = clock;
        this.failures = Counter.builder(FAILURE_METRIC)
                .description("Outbox events a handler failed to consume; they stay pending and are retried")
                .register(meterRegistry);
        this.lag = Timer.builder(LAG_METRIC)
                .description("Time from an event's occurrence to its publication")
                .publishPercentileHistogram()
                .serviceLevelObjectives(LAG_TARGET)
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(meterRegistry);
    }

    /**
     * Dispatches one batch of at most {@value #BATCH_SIZE} pending events, in insertion order.
     *
     * @return the number of events marked published; events whose handler failed are not counted
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int dispatchBatch() {
        List<DomainEvent> batch = jdbc.sql(SELECT_PENDING)
                .param("limit", BATCH_SIZE)
                .query(OutboxDispatcher::toEvent)
                .list();
        int dispatched = 0;
        for (DomainEvent event : batch) {
            if (dispatch(event)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    private boolean dispatch(DomainEvent event) {
        MDC.put(LogFields.ORIGIN_TRACE_ID, event.traceId());
        try {
            return dispatchCorrelated(event);
        } finally {
            MDC.remove(LogFields.ORIGIN_TRACE_ID);
        }
    }

    private boolean dispatchCorrelated(DomainEvent event) {
        jdbc.sql("SAVEPOINT outbox_dispatch").update();
        try {
            handlers.orderedStream().forEach(handler -> handler.handle(event));
        } catch (RuntimeException failure) {
            jdbc.sql("ROLLBACK TO SAVEPOINT outbox_dispatch").update();
            failures.increment();
            // Only the exception type: messages of database errors can quote payload values.
            log.error(
                    "Outbox event {} of type {} could not be dispatched ({}); it stays pending",
                    event.eventId(),
                    event.type(),
                    failure.getClass().getName());
            if (TransactionAspectSupport.currentTransactionStatus().isRollbackOnly()) {
                throw new IllegalStateException(
                        "A handler marked the dispatch transaction for rollback; the whole batch stays pending",
                        failure);
            }
            return false;
        }
        jdbc.sql("RELEASE SAVEPOINT outbox_dispatch").update();
        Instant publishedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql(MARK_PUBLISHED)
                .param("publishedAt", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
                .param("id", event.eventId())
                .update();
        Duration sinceOccurrence = Duration.between(event.occurredAt(), publishedAt);
        lag.record(sinceOccurrence.isNegative() ? Duration.ZERO : sinceOccurrence);
        log.info("Outbox event {} of type {} published", event.eventId(), event.type());
        return true;
    }

    private static DomainEvent toEvent(ResultSet row, int rowNumber) throws SQLException {
        return new DomainEvent(
                row.getObject("id", UUID.class),
                row.getString("type"),
                row.getInt("schema_version"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("workspace_id", UUID.class),
                row.getString("trace_id"),
                row.getString("payload"));
    }
}
