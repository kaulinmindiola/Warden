package io.github.kaulinmindiola.warden.iam;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only user facts for other modules (SDD V09). Other modules reference users only by id and
 * read what they need through this interface, never through {@code iam} entities.
 */
public interface UserDirectory {

    /** The user with this id, if the account exists. */
    Optional<UserSummary> findById(UUID userId);

    /** The users with these ids, keyed by id; unknown ids are left out. */
    Map<UUID, UserSummary> findByIds(Set<UUID> userIds);
}
