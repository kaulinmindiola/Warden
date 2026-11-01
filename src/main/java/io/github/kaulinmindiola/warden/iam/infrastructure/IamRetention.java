package io.github.kaulinmindiola.warden.iam.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily purge of credentials that can no longer be used (SDD V06):
 *
 * <ul>
 *   <li>single-use tokens {@link #TOKEN_RETENTION} after they expire, consumed or not;
 *   <li>sessions, with their refresh tokens, {@link #SESSION_RETENTION} after they were revoked,
 *       reached their absolute expiry, or saw their last refresh token expire.
 * </ul>
 *
 * <p>Only hashes are stored, so nothing here is a usable secret; the purge limits how much
 * account history is kept. Revoked sessions stay well beyond the 630 s window that revocation
 * reconciliation reads, and any access token of a purged session expired long before. {@link
 * IamPurgeSchedule} runs it every day while workers are switched on; every instance runs it, and
 * deleting the same rows twice is harmless. A failure is logged and the next day's run catches up.
 */
@Component
public class IamRetention {

    /** How long a single-use token is kept after it expires. */
    public static final Duration TOKEN_RETENTION = Duration.ofDays(7);

    /** How long a session is kept once it can no longer be used. */
    public static final Duration SESSION_RETENTION = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(IamRetention.class);

    private static final String PURGE_TOKENS = """
            DELETE FROM one_time_token
            WHERE expires_at < :cutoff
            """;

    /** Sessions revoked, absolutely expired or without a refresh token alive since the cutoff. */
    private static final String DEAD_SESSIONS = """
            SELECT s.id
            FROM auth_session s
            WHERE s.revoked_at < :cutoff
               OR s.absolute_expires_at < :cutoff
               OR NOT EXISTS (SELECT 1 FROM refresh_token r WHERE r.session_id = s.id AND r.expires_at >= :cutoff)
            """;

    private static final String PURGE_REFRESH_TOKENS =
            "DELETE FROM refresh_token WHERE session_id IN (" + DEAD_SESSIONS + ")";

    private static final String PURGE_SESSIONS = "DELETE FROM auth_session WHERE id IN (" + DEAD_SESSIONS + ")";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public IamRetention(JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** One scheduled run: a failure is logged instead of propagated. */
    void scheduledPurge() {
        try {
            purge();
        } catch (RuntimeException failure) {
            log.warn(
                    "Credential purge failed ({}); it will run again tomorrow",
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Deletes the expired single-use tokens and the dead sessions past their retention, by the
     * application clock. Sessions and their refresh tokens go in one transaction.
     */
    public Purged purge() {
        Instant now = clock.instant();
        OffsetDateTime tokenCutoff = utc(now.minus(TOKEN_RETENTION));
        OffsetDateTime sessionCutoff = utc(now.minus(SESSION_RETENTION));
        int tokens = jdbc.sql(PURGE_TOKENS).param("cutoff", tokenCutoff).update();
        Integer sessions = transactions.execute(status -> {
            jdbc.sql(PURGE_REFRESH_TOKENS).param("cutoff", sessionCutoff).update();
            return jdbc.sql(PURGE_SESSIONS).param("cutoff", sessionCutoff).update();
        });
        Purged purged = new Purged(tokens, sessions == null ? 0 : sessions);
        log.info("Purged {} single-use tokens and {} sessions", purged.oneTimeTokens(), purged.sessions());
        return purged;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** What one run deleted. */
    public record Purged(int oneTimeTokens, int sessions) {}
}
