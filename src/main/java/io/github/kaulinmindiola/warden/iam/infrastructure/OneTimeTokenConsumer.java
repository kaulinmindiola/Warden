package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.OneTimeToken;
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
 * Consumes a one-time token with a single conditional update (BR-006). When several requests
 * present the same token at once, PostgreSQL locks the row and re-evaluates the condition for the
 * others after the first commits, so exactly one of them consumes it.
 */
@Component
public class OneTimeTokenConsumer {

    private static final String CONSUME = """
            UPDATE one_time_token
            SET consumed_at = :now
            WHERE token_hash = :hash
              AND purpose = :purpose
              AND consumed_at IS NULL
              AND invalidated_at IS NULL
              AND expires_at > :now
            RETURNING user_id
            """;

    private final JdbcClient jdbc;

    public OneTimeTokenConsumer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marks the token consumed if it is active.
     *
     * @return the owner of the token, or empty if it is unknown, used, invalidated or expired
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> consume(TokenHash hash, OneTimeToken.Purpose purpose, Instant now) {
        return jdbc.sql(CONSUME)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("hash", hash.bytes())
                .param("purpose", purpose.name())
                .query(UUID.class)
                .optional();
    }
}
