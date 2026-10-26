package io.github.kaulinmindiola.warden.workspace.infrastructure;

import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import io.github.kaulinmindiola.warden.workspace.domain.Workspace;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

/** Workspaces. Only the operations the use cases need are exposed. */
public interface WorkspaceRepository extends Repository<Workspace, UUID>, PersistOperations<Workspace> {

    Optional<Workspace> findById(UUID id);

    /**
     * The workspace if it is not deleted, locked with {@code SELECT … FOR UPDATE} until the end of
     * the transaction. Serializes membership changes of one workspace (SDD V14-A4).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Workspace> findByIdAndDeletedAtIsNull(UUID id);
}
