package io.github.kaulinmindiola.warden.iam;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only user facts for other modules (SDD V09). Other modules reference users only by id and
 * read what they need through this interface, never through {@code iam} entities. E-mail
 * normalization lives here too, so every module compares addresses with the rule accounts use
 * (BR-009).
 */
public interface UserDirectory {

    /** The user with this id, if the account exists. */
    Optional<UserSummary> findById(UUID userId);

    /** The users with these ids, keyed by id; unknown ids are left out. */
    Map<UUID, UserSummary> findByIds(Set<UUID> userIds);

    /**
     * The user with this e-mail, normalized first.
     *
     * @return empty if the address is malformed or has no account
     */
    Optional<UserSummary> findByEmail(String rawEmail);

    /**
     * The address in the normalized form accounts use: trimmed and lower-cased.
     *
     * @return empty if the address is missing or malformed
     */
    Optional<String> normalizeEmail(String rawEmail);
}
