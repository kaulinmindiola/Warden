package io.github.kaulinmindiola.warden.workspace.infrastructure;

import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import io.github.kaulinmindiola.warden.workspace.domain.Workspace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Workspaces. Only the operations the use cases need are exposed. */
public interface WorkspaceRepository extends Repository<Workspace, UUID>, PersistOperations<Workspace> {

    Optional<Workspace> findById(UUID id);
}
