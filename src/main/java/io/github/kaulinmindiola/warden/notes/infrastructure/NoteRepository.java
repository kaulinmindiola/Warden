package io.github.kaulinmindiola.warden.notes.infrastructure;

import io.github.kaulinmindiola.warden.notes.domain.Note;
import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Notes. Every operation takes the workspace explicitly (SDD V07 layer L2) and runs inside a
 * transaction scoped to that workspace, where Row Level Security applies (layer L3).
 */
public interface NoteRepository extends Repository<Note, UUID>, PersistOperations<Note> {

    Optional<Note> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Page<Note> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    /** @return 1 if the note existed in the workspace and was deleted, 0 otherwise */
    @Modifying
    @Query("delete from Note n where n.id = :id and n.workspaceId = :workspaceId")
    int deleteInWorkspace(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId);
}
