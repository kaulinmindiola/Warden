package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks a refresh token as used with a single conditional update (SDD V14-A1). Concurrent callers
 * presenting the same token block on the row lock; after the winner commits, PostgreSQL
 * re-evaluates the condition and returns no row for the others, so exactly one succeeds.
 */
@Component
public class RefreshTokenConsumer {

    private static final String CONSUME = """
            UPDATE refresh_token
            SET used_at = :now
            WHERE token_hash = :hash
              AND used_at IS NULL
              AND expires_at > :now
            RETURNING session_id
            """;

    private final JdbcClient jdbc;

    public RefreshTokenConsumer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marks the token used if it is unused and unexpired.
     *
     * @return the session of the token, or empty if it is unknown, already used or expired
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> consume(TokenHash hash, Instant now) {
        return jdbc.sql(CONSUME)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("hash", hash.bytes())
                .query(UUID.class)
                .optional();
    }
}
