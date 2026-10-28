package io.github.kaulinmindiola.warden.workspace.infrastructure;

import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import io.github.kaulinmindiola.warden.workspace.domain.Invitation;
import io.github.kaulinmindiola.warden.workspace.domain.InvitationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Invitations. Management operations name the workspace and run in a transaction scoped to it;
 * acceptance runs in a transaction of the token holder (SDD V07). Row Level Security applies in
 * both.
 */
public interface InvitationRepository extends Repository<Invitation, UUID>, PersistOperations<Invitation> {

    Optional<Invitation> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /** Invitations of an address in a workspace with a stored status, expired or not. */
    List<Invitation> findByWorkspaceIdAndEmailAndStatus(UUID workspaceId, String email, InvitationStatus status);

    /** Invitations with a stored status that expire after {@code now}. */
    Page<Invitation> findByWorkspaceIdAndStatusAndExpiresAtAfter(
            UUID workspaceId, InvitationStatus status, Instant now, Pageable pageable);

    /** The invitation whose token has this SHA-256. Needs a transaction of the token holder. */
    @Query("select i from Invitation i where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHash(@Param("tokenHash") byte[] tokenHash);

    /**
     * Consumes an invitation if it is still pending and unexpired at {@code now} (REQ-FUNC-036,
     * BR-006). The condition is evaluated under the row lock, so a concurrent revocation or
     * acceptance makes it fail.
     *
     * @return 1 if it was accepted, 0 otherwise
     */
    @Modifying
    @Query("""
            update Invitation i
            set i.status = io.github.kaulinmindiola.warden.workspace.domain.InvitationStatus.ACCEPTED
            where i.id = :id
              and i.status = io.github.kaulinmindiola.warden.workspace.domain.InvitationStatus.PENDING
              and i.expiresAt > :now
            """)
    int markAccepted(@Param("id") UUID id, @Param("now") Instant now);
}
