package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.AuthSession;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes every session of a user with a single conditional update (DEC-022, SDD V11). Sessions
 * already revoked keep their original reason; the ones this call revokes are returned, so the
 * caller can mark them in the revocation store after the commit.
 */
@Component
public class AuthSessionRevoker {

    private static final String REVOKE_ALL = """
            UPDATE auth_session
            SET revoked_at = :now, revoke_reason = :reason
            WHERE user_id = :userId
              AND revoked_at IS NULL
            RETURNING id
            """;

    private final JdbcClient jdbc;

    public AuthSessionRevoker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Revokes all the user's sessions that are not revoked yet, including those past their
     * absolute expiry, whose last access tokens may still be within their lifetime.
     *
     * @return the sessions revoked by this call
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> revokeAll(UUID userId, AuthSession.RevokeReason reason, Instant now) {
        return jdbc.sql(REVOKE_ALL)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("reason", reason.name())
                .param("userId", userId)
                .query(UUID.class)
                .list();
    }
}
