package io.github.kaulinmindiola.warden.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Daily purge of outbox events published more than {@link #RETENTION} ago (REQ-FUNC-063, DEC-007).
 * Events carry the recipients' personal data, so they are kept only long enough to investigate a
 * delivery. An unpublished event is never deleted, however old: it has not reached its consumers.
 *
 * <p>{@link OutboxPurgeSchedule} runs it every day while workers are switched on. Every instance
 * runs the job; deleting the same events twice is harmless. A failure is logged and the next
 * day's run catches up.
 */
@Component
public class OutboxRetention {

    /** How long a published event is kept (REQ-FUNC-063). */
    public static final Duration RETENTION = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

    private static final String PURGE = """
            DELETE FROM outbox_event
            WHERE published_at IS NOT NULL AND published_at < :cutoff
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public OutboxRetention(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One scheduled run: a failure is logged instead of propagated. */
    void scheduledPurge() {
        try {
            purge();
        } catch (RuntimeException failure) {
            log.warn(
                    "Outbox purge failed ({}); it will run again tomorrow",
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Deletes the events published more than {@link #RETENTION} ago by the application clock.
     *
     * @return how many events were deleted
     */
    public int purge() {
        Instant cutoff = clock.instant().minus(RETENTION);
        int purged = jdbc.sql(PURGE)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
        log.info("Purged {} outbox events published before {}", purged, cutoff);
        return purged;
    }
}
