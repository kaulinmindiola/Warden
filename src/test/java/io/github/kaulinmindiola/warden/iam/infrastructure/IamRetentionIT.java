package io.github.kaulinmindiola.warden.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.IntegrationTest;
import java.security.SecureRandom;
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
 * Retention of credentials that can no longer be used (SDD V06): single-use tokens 7 days after
 * they expire; sessions, with their refresh tokens, 30 days after they were revoked, reached their
 * absolute expiry or saw their last refresh token expire. Rows are seeded as the superuser at
 * exact ages from the test clock, each case one second on either side of its limit.
 */
class IamRetentionIT extends IntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOKENS = IamRetention.TOKEN_RETENTION;
    private static final Duration SESSIONS = IamRetention.SESSION_RETENTION;

    @Autowired
    private IamRetention retention;

    private Instant now;
    private UUID user;

    @BeforeEach
    void seedAUser() {
        clock.setInstant(Instant.now().truncatedTo(ChronoUnit.MICROS));
        now = clock.instant();
        user = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO user_account (id, email_normalized, password_hash, registration_locale, created_at)
                        VALUES (?, ?, 'not-a-real-hash', 'ES', ?)
                        """)
                .param(user)
                .param("retention-" + user + "@mail.test")
                .param(utc(now.minus(Duration.ofDays(400))))
                .update();
    }

    @Test
    void singleUseTokensAreDeletedSevenDaysAfterTheyExpire() {
        UUID expired = seedToken(now.minus(TOKENS).minusSeconds(1));
        UUID justInside = seedToken(now.minus(TOKENS).plusSeconds(1));
        UUID active = seedToken(now.plus(Duration.ofMinutes(30)));

        IamRetention.Purged purged = retention.purge();

        assertThat(purged.oneTimeTokens()).isPositive();
        assertThat(exists("one_time_token", expired))
                .as("expired 7 d + 1 s ago")
                .isFalse();
        assertThat(exists("one_time_token", justInside))
                .as("expired 7 d - 1 s ago")
                .isTrue();
        assertThat(exists("one_time_token", active)).as("not expired").isTrue();
    }

    @Test
    void revokedSessionsAreDeletedWithTheirRefreshTokensThirtyDaysAfterTheRevocation() {
        Instant longAgo = now.minus(Duration.ofDays(90));
        UUID revoked = seedSession(
                longAgo, now.plus(Duration.ofDays(1)), now.minus(SESSIONS).minusSeconds(1));
        UUID revokedToken = seedRefreshToken(revoked, now.plus(Duration.ofDays(1)));
        UUID recentlyRevoked = seedSession(
                longAgo, now.plus(Duration.ofDays(1)), now.minus(SESSIONS).plusSeconds(1));
        seedRefreshToken(recentlyRevoked, now.plus(Duration.ofDays(1)));

        IamRetention.Purged purged = retention.purge();

        assertThat(purged.sessions()).isPositive();
        assertThat(exists("auth_session", revoked)).as("revoked 30 d + 1 s ago").isFalse();
        assertThat(exists("refresh_token", revokedToken))
                .as("its refresh token")
                .isFalse();
        assertThat(exists("auth_session", recentlyRevoked))
                .as("revoked 30 d - 1 s ago")
                .isTrue();
    }

    @Test
    void expiredSessionsAreDeletedThirtyDaysAfterTheyCouldLastBeUsed() {
        Instant longAgo = now.minus(Duration.ofDays(90));
        UUID absolutelyExpired = seedSession(longAgo, now.minus(SESSIONS).minusSeconds(1), null);
        seedRefreshToken(absolutelyExpired, now.minus(SESSIONS).minusSeconds(1));
        UUID inactive = seedSession(longAgo, now.plus(Duration.ofDays(10)), null);
        seedRefreshToken(inactive, now.minus(SESSIONS).minusSeconds(1));
        UUID recentlyInactive = seedSession(longAgo, now.plus(Duration.ofDays(10)), null);
        seedRefreshToken(recentlyInactive, now.minus(SESSIONS).plusSeconds(1));
        UUID active = seedSession(now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(29)), null);
        seedRefreshToken(active, now.plus(Duration.ofDays(6)));

        retention.purge();

        assertThat(exists("auth_session", absolutelyExpired))
                .as("absolute expiry 30 d + 1 s ago")
                .isFalse();
        assertThat(exists("auth_session", inactive))
                .as("last refresh token expired 30 d + 1 s ago")
                .isFalse();
        assertThat(exists("auth_session", recentlyInactive))
                .as("last refresh token expired 30 d - 1 s ago")
                .isTrue();
        assertThat(exists("auth_session", active)).as("in use").isTrue();
    }

    @Test
    void theScheduledJobPurgesExpiredCredentials() {
        UUID expired = seedToken(now.minus(Duration.ofDays(8)));

        retention.scheduledPurge();

        assertThat(exists("one_time_token", expired)).isFalse();
    }

    private UUID seedToken(Instant expiresAt) {
        UUID id = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO one_time_token (id, user_id, purpose, token_hash, expires_at)
                        VALUES (?, ?, 'PASSWORD_RESET', ?, ?)
                        """)
                .param(id)
                .param(user)
                .param(randomHash())
                .param(utc(expiresAt))
                .update();
        return id;
    }

    private UUID seedSession(Instant createdAt, Instant absoluteExpiresAt, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO auth_session (id, user_id, created_at, last_used_at, absolute_expires_at,
                                                  revoked_at, revoke_reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(id)
                .param(user)
                .param(utc(createdAt))
                .param(utc(createdAt))
                .param(utc(absoluteExpiresAt))
                .param(revokedAt == null ? null : utc(revokedAt))
                .param(revokedAt == null ? null : "LOGOUT")
                .update();
        return id;
    }

    private UUID seedRefreshToken(UUID session, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO refresh_token (id, session_id, token_hash, issued_at, expires_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .param(id)
                .param(session)
                .param(randomHash())
                .param(utc(expiresAt.minus(Duration.ofDays(7))))
                .param(utc(expiresAt))
                .update();
        return id;
    }

    private static boolean exists(String table, UUID id) {
        return superuser()
                        .sql("SELECT count(*) FROM " + table + " WHERE id = ?")
                        .param(id)
                        .query(Long.class)
                        .single()
                == 1;
    }

    private static byte[] randomHash() {
        byte[] hash = new byte[32];
        RANDOM.nextBytes(hash);
        return hash;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
