package io.github.kaulinmindiola.warden.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Retention of the outbox (REQ-FUNC-063): an event published more than 7 days ago is deleted, one
 * published less long ago is kept, and an unpublished event is kept whatever its age. Events are
 * seeded as the superuser at exact ages from the test clock; the purge runs as the application.
 */
class OutboxRetentionIT extends IntegrationTest {

    private static final Duration WEEK = OutboxRetention.RETENTION;

    @Autowired
    private OutboxRetention retention;

    private Instant now;

    @BeforeEach
    void freezeTheClock() {
        clock.setInstant(Instant.now().truncatedTo(ChronoUnit.MICROS));
        now = clock.instant();
    }

    @Test
    @Verifies("REQ-FUNC-063")
    void anEventPublishedSevenDaysAndOneSecondAgoIsDeletedAndAYoungerOneIsKept() {
        UUID expired = seed(now.minus(Duration.ofDays(30)), now.minus(WEEK).minusSeconds(1));
        UUID justInside = seed(now.minus(Duration.ofDays(30)), now.minus(WEEK).plusSeconds(1));

        int purged = retention.purge();

        assertThat(purged).isPositive();
        assertThat(exists(expired)).as("published 7 d + 1 s ago").isFalse();
        assertThat(exists(justInside)).as("published 7 d - 1 s ago").isTrue();
    }

    @Test
    @Verifies("REQ-FUNC-063")
    void anUnpublishedEventIsKeptWhateverItsAge() {
        UUID unpublished = seed(now.minus(Duration.ofDays(400)), null);
        try {
            retention.purge();

            assertThat(exists(unpublished)).isTrue();
        } finally {
            superuser()
                    .sql("DELETE FROM outbox_event WHERE id = ?")
                    .param(unpublished)
                    .update();
        }
    }

    @Test
    void theScheduledJobPurgesPublishedEvents() {
        UUID expired = seed(now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(8)));

        retention.scheduledPurge();

        assertThat(exists(expired)).isFalse();
    }

    private static UUID seed(Instant occurredAt, Instant publishedAt) {
        UUID id = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO outbox_event (id, type, schema_version, workspace_id, payload, trace_id,
                                                  occurred_at, published_at)
                        VALUES (?, 'test.RetentionProbe', 1, NULL, '{}'::jsonb, 'retention', ?, ?)
                        """)
                .param(id)
                .param(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .param(publishedAt == null ? null : OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
                .update();
        return id;
    }

    private static boolean exists(UUID id) {
        return superuser()
                        .sql("SELECT count(*) FROM outbox_event WHERE id = ?")
                        .param(id)
                        .query(Long.class)
                        .single()
                == 1;
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
