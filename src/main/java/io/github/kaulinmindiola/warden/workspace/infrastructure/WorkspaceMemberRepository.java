package io.github.kaulinmindiola.warden.workspace.infrastructure;

import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import io.github.kaulinmindiola.warden.workspace.domain.WorkspaceMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Workspace memberships. Callers run inside a tenant transaction: Row Level Security returns no
 * row otherwise.
 */
public interface WorkspaceMemberRepository
        extends Repository<WorkspaceMember, WorkspaceMember.Key>, PersistOperations<WorkspaceMember> {

    /**
     * The non-deleted workspaces of a user with the user's stored role, oldest first. The order is
     * fixed here, so the pageable must be unsorted. Needs a transaction scoped to the user.
     */
    @Query(value = """
                    select new io.github.kaulinmindiola.warden.workspace.infrastructure.MemberWorkspaceRow(
                        w.id, w.name, m.role, w.createdAt)
                    from WorkspaceMember m join Workspace w on w.id = m.workspaceId
                    where m.userId = :userId and w.deletedAt is null
                    order by w.createdAt, w.id
                    """, countQuery = """
                    select count(m)
                    from WorkspaceMember m join Workspace w on w.id = m.workspaceId
                    where m.userId = :userId and w.deletedAt is null
                    """)
    Page<MemberWorkspaceRow> findWorkspacesOf(@Param("userId") UUID userId, Pageable pageable);

    /**
     * The stored role of a user in a workspace that is not deleted. Needs a transaction scoped to
     * the user or to the workspace.
     */
    @Query("""
            select m.role
            from WorkspaceMember m join Workspace w on w.id = m.workspaceId
            where m.workspaceId = :workspaceId and m.userId = :userId and w.deletedAt is null
            """)
    Optional<String> findActiveRole(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId);

    /** Ids of the user's non-deleted workspaces, oldest first. Needs a transaction scoped to the user. */
    @Query("""
            select m.workspaceId
            from WorkspaceMember m join Workspace w on w.id = m.workspaceId
            where m.userId = :userId and w.deletedAt is null
            order by w.createdAt, w.id
            """)
    List<UUID> findActiveWorkspaceIds(@Param("userId") UUID userId);

    /** The members of a workspace. Needs a transaction scoped to the workspace. */
    Page<WorkspaceMember> findByWorkspaceId(UUID workspaceId, Pageable pageable);
}
