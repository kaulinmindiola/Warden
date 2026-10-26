package io.github.kaulinmindiola.warden.shared.security;

import java.util.Objects;
import java.util.UUID;

/**
 * The caller's membership in the workspace of the current request, resolved by {@link
 * MembershipLookup} on every request (BR-004). The authorization interceptor stores it as the
 * request attribute {@link #REQUEST_ATTRIBUTE}; handlers can read it with {@code
 * @RequestAttribute}.
 *
 * @param workspaceId the workspace of the route
 * @param role the caller's role in it
 */
public record WorkspaceAccess(UUID workspaceId, Role role) {

    /** Request attribute holding the access granted by the interceptor. */
    public static final String REQUEST_ATTRIBUTE = "warden.workspaceAccess";

    public WorkspaceAccess {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(role, "role");
    }
}
