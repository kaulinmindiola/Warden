package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.AuthSession;
import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Login sessions. Only the operations the use cases need are exposed. */
public interface AuthSessionRepository extends Repository<AuthSession, UUID>, PersistOperations<AuthSession> {

    Optional<AuthSession> findById(UUID id);

    /** Sessions of the user that are neither revoked nor past their absolute expiry. */
    Page<AuthSession> findByUserIdAndRevokedAtIsNullAndAbsoluteExpiresAtAfter(
            UUID userId, Instant now, Pageable pageable);

    /** Whether the session exists and belongs to the user, revoked or not. */
    boolean existsByIdAndUserId(UUID id, UUID userId);

    /** Whether the session exists and is not revoked; the fallback when Redis cannot answer. */
    boolean existsByIdAndRevokedAtIsNull(UUID id);

    /**
     * Revokes the session if it belongs to the user and is not revoked yet. When several requests
     * revoke the same session at once, only one of them changes the row.
     *
     * @return 1 if this call revoked the session, 0 otherwise
     */
    @Modifying
    @Query("""
            update AuthSession s
            set s.revokedAt = :now, s.revokeReason = :reason
            where s.id = :id and s.userId = :userId and s.revokedAt is null
            """)
    int revokeIfActive(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("reason") AuthSession.RevokeReason reason,
            @Param("now") Instant now);
}
