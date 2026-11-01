package io.github.kaulinmindiola.warden.shared.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The {@code warden_outbox_pending} gauge of SDD V19 (REQ-OBS-002): how many events are waiting to
 * be published, across every workspace ({@code outbox_event} has no row-level security).
 *
 * <p>The gauge shows the count of the last refresh, so a scrape never queries the database; the
 * notification worker refreshes it every few seconds while workers are switched on. If PostgreSQL
 * cannot be read, the last count is kept and the next refresh tries again.
 */
@Component
public class OutboxMetrics {

    public static final String PENDING_METRIC = "warden.outbox.pending";

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private static final String COUNT_PENDING = "SELECT count(*) FROM outbox_event WHERE published_at IS NULL";

    private final JdbcClient jdbc;
    private final AtomicLong pending = new AtomicLong();

    public OutboxMetrics(JdbcClient jdbc, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        Gauge.builder(PENDING_METRIC, pending, AtomicLong::get)
                .description("Outbox events not yet published, as of the last refresh")
                .strongReference(true)
                .register(meterRegistry);
    }

    /**
     * Counts the pending events now and shows the count in the gauge.
     *
     * @return the number of pending events
     */
    public long refreshPending() {
        Long count = jdbc.sql(COUNT_PENDING).query(Long.class).single();
        pending.set(count);
        return count;
    }

    /** One scheduled refresh: a failure is logged and the gauge keeps its last count. */
    public void scheduledRefresh() {
        try {
            refreshPending();
        } catch (RuntimeException failure) {
            log.warn(
                    "Outbox pending count could not be refreshed ({})",
                    failure.getClass().getName());
        }
    }
}
