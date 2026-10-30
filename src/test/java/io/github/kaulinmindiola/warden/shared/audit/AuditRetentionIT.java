package io.github.kaulinmindiola.warden.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Retention of the audit log (REQ-SEC-016): records older than 365 days are purged through the
 * {@code SECURITY DEFINER} function, the only way the application role can delete them, and no
 * cutoff ever purges a younger record.
 *
 * <p>The function also bounds the cutoff by the database clock, so the test clock is aligned with
 * it first; records are then seeded at exact ages relative to that instant.
 */
class AuditRetentionIT extends IntegrationTest {

    private static final Duration YEAR = AuditRetention.RETENTION;

    @Autowired
    private AuditRetention retention;

    /** The application's own connection, as {@code warden_app}. */
    @Autowired
    private JdbcClient applicationJdbc;

    private Instant now;

    @BeforeEach
    void alignTheClockWithTheDatabase() {
        OffsetDateTime databaseNow = superuser()
                .sql("SELECT clock_timestamp()")
                .query(OffsetDateTime.class)
                .single();
        clock.setInstant(databaseNow.toInstant());
        now = clock.instant();
    }

    @Test
    @Verifies("REQ-SEC-016")
    void aRecordOf365DaysAndOneSecondIsPurgedAndOneOf364DaysIsKept() {
        UUID expired = seed(now.minus(YEAR).minusSeconds(1));
        UUID justInside = seed(now.minus(YEAR).plusSeconds(1));
        UUID recent = seed(now.minus(Duration.ofDays(364)));

        long purged = retention.purge();

        assertThat(purged).isPositive();
        assertThat(exists(expired)).as("365 d + 1 s").isFalse();
        assertThat(exists(justInside)).as("365 d - 1 s").isTrue();
        assertThat(exists(recent)).as("364 d").isTrue();
    }

    @Test
    @Verifies("REQ-SEC-016")
    void noCutoffPurgesARecordYoungerThan365Days() {
        UUID recent = seed(now.minus(Duration.ofDays(364)));

        clock.advance(Duration.ofDays(30));
        retention.purge();
        applicationJdbc
                .sql("SELECT purge_audit_log(now() + interval '1 day')")
                .query(Long.class)
                .single();

        assertThat(exists(recent)).isTrue();
    }

    @Test
    void theScheduledJobPurgesExpiredRecords() {
        UUID expired = seed(now.minus(Duration.ofDays(366)));

        retention.scheduledPurge();

        assertThat(exists(expired)).isFalse();
    }

    @Test
    @Verifies("REQ-SEC-016")
    void thePurgeRunsWithTheOwnersRightsAndOnlyTheApplicationRoleMayCallIt() {
        boolean securityDefiner = superuser()
                .sql("SELECT prosecdef FROM pg_proc WHERE proname = 'purge_audit_log'")
                .query(Boolean.class)
                .single();
        String grants = superuser()
                .sql("SELECT proacl::text FROM pg_proc WHERE proname = 'purge_audit_log'")
                .query(String.class)
                .single();
        boolean applicationMayCall = superuser()
                .sql("SELECT has_function_privilege('warden_app', 'purge_audit_log(timestamptz)', 'EXECUTE')")
                .query(Boolean.class)
                .single();

        assertThat(securityDefiner).isTrue();
        assertThat(applicationMayCall).isTrue();
        assertThat(grants).as("no PUBLIC entry").doesNotContain("{=X").doesNotContain(",=X");
    }

    /** An audit record of the given age, seeded as the superuser; returns its unique actor. */
    private static UUID seed(Instant occurredAt) {
        UUID actor = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO audit_log (id, occurred_at, actor_user_id, action, outcome, trace_id)
                        VALUES (?, ?, ?, 'LOGIN', 'SUCCESS', ?)
                        """)
                .param(UUID.randomUUID())
                .param(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .param(actor)
                .param("0".repeat(32))
                .update();
        return actor;
    }

    private static boolean exists(UUID actor) {
        return superuser()
                        .sql("SELECT count(*) FROM audit_log WHERE actor_user_id = ?")
                        .param(actor)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
