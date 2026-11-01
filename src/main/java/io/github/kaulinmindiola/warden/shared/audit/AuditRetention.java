package io.github.kaulinmindiola.warden.shared.audit;

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
 * Daily purge of audit records older than {@link #RETENTION} (REQ-SEC-016, SDD V06).
 *
 * <p>The application role cannot delete audit records; the purge goes through the {@code
 * SECURITY DEFINER} function {@code purge_audit_log(cutoff)}, which never deletes a record younger
 * than 365 days whatever cutoff it receives. {@link AuditPurgeSchedule} runs it every day while
 * workers are switched on. Every instance runs the job; deleting the same old records twice is
 * harmless. A failure is logged and the next day's run catches up.
 */
@Component
public class AuditRetention {

    /** How long audit records are kept (REQ-SEC-016). The database enforces the same floor. */
    public static final Duration RETENTION = Duration.ofDays(365);

    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditRetention(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** One scheduled run: a failure is logged instead of propagated. */
    void scheduledPurge() {
        try {
            purge();
        } catch (RuntimeException failure) {
            log.warn(
                    "Audit purge failed ({}); it will run again tomorrow",
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Deletes the audit records older than {@link #RETENTION} by the application clock.
     *
     * @return how many records were deleted
     */
    public long purge() {
        Instant cutoff = clock.instant().minus(RETENTION);
        Long purged = jdbc.sql("SELECT purge_audit_log(:cutoff)")
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .query(Long.class)
                .single();
        long count = purged == null ? 0 : purged;
        log.info("Purged {} audit records older than {}", count, cutoff);
        return count;
    }
}
