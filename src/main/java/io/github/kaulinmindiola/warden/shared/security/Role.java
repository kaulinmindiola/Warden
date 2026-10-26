package io.github.kaulinmindiola.warden.shared.security;

import java.util.Objects;
import java.util.Optional;

/**
 * Role of a user in a workspace (SDD V12). Constants are declared from least to most privileged;
 * that order is the hierarchy, so never reorder them. The name is stored in the database.
 */
public enum Role {
    /** Reads the workspace and its resources. */
    VIEWER,
    /** Also creates and edits resources. */
    MEMBER,
    /** Also manages members and invitations, except OWNERs. */
    ADMIN,
    /** Full control, including OWNERs and deleting the workspace. */
    OWNER;

    /** Whether this role grants at least the privileges of {@code minimum}. */
    public boolean isAtLeast(Role minimum) {
        Objects.requireNonNull(minimum, "minimum");
        return compareTo(minimum) >= 0;
    }

    /**
     * The role with this stored name. An unknown value maps to no role, so the caller can deny
     * access instead of failing open (REQ-SEC-011).
     */
    public static Optional<Role> fromStored(String storedName) {
        if (storedName == null) {
            return Optional.empty();
        }
        for (Role role : values()) {
            if (role.name().equals(storedName)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
