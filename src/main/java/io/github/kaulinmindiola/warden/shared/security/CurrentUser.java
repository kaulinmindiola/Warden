package io.github.kaulinmindiola.warden.shared.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The authenticated caller, taken from a validated access token. Controllers receive it with
 * {@code @AuthenticationPrincipal CurrentUser}.
 *
 * @param userId the {@code sub} claim
 * @param sessionId the {@code sid} claim
 */
public record CurrentUser(UUID userId, UUID sessionId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Name of the session-id claim in access tokens. */
    public static final String SESSION_CLAIM = "sid";

    public CurrentUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
