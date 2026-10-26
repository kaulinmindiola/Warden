package io.github.kaulinmindiola.warden.shared.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves memberships for authorization (SDD V09). Defined by {@code shared}, implemented by
 * the {@code workspace} module, so {@code shared} depends on no domain module. Results are never
 * cached: role changes and removals apply on the next request (BR-004, REQ-FUNC-022).
 */
public interface MembershipLookup {

    /**
     * The caller's access to a workspace.
     *
     * @return empty if the user is not a member, the workspace does not exist or is deleted
     *     (BR-012), or the stored role is unknown, which the implementation must log
     * @throws MembershipUnavailableException if the membership cannot be read
     */
    Optional<WorkspaceAccess> find(UUID workspaceId, UUID userId);

    /**
     * Workspaces the user is a member of, excluding deleted ones.
     *
     * @throws MembershipUnavailableException if the memberships cannot be read
     */
    List<UUID> workspaceIdsOf(UUID userId);
}
