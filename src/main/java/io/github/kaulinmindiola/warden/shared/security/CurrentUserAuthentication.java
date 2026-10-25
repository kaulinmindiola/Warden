package io.github.kaulinmindiola.warden.shared.security;

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Authentication of a request carrying a valid access token. It has no authorities: roles are
 * resolved per workspace on every request (ADR-003), not from the token. The token itself is not
 * kept as credentials, so it cannot leak through the security context.
 */
public class CurrentUserAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CurrentUser currentUser;

    public CurrentUserAuthentication(CurrentUser currentUser) {
        super(List.of());
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        setAuthenticated(true);
    }

    @Override
    public CurrentUser getPrincipal() {
        return currentUser;
    }

    @Override
    public Object getCredentials() {
        return null;
    }
}
