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
import org.springframework.data.repository.Repository;

/**
 * Invitations. Every operation names the workspace and runs in a transaction scoped to it, where
 * Row Level Security applies (SDD V07).
 */
public interface InvitationRepository extends Repository<Invitation, UUID>, PersistOperations<Invitation> {

    Optional<Invitation> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /** Invitations of an address in a workspace with a stored status, expired or not. */
    List<Invitation> findByWorkspaceIdAndEmailAndStatus(UUID workspaceId, String email, InvitationStatus status);

    /** Invitations with a stored status that expire after {@code now}. */
    Page<Invitation> findByWorkspaceIdAndStatusAndExpiresAtAfter(
            UUID workspaceId, InvitationStatus status, Instant now, Pageable pageable);
}
