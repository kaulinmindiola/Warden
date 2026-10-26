package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects access tokens of revoked sessions (REQ-FUNC-018). The revocation store answers while it
 * is reachable; when it cannot, PostgreSQL decides, failing closed: a session that is revoked or
 * does not exist is rejected (ADR-007).
 */
public class SessionRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INACTIVE_SESSION =
            new OAuth2Error("invalid_token", "The session is no longer active", null);

    private final RevocationStore revocationStore;
    private final AuthSessionRepository sessions;

    public SessionRevocationValidator(RevocationStore revocationStore, AuthSessionRepository sessions) {
        this.revocationStore = revocationStore;
        this.sessions = sessions;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        UUID sessionId = parse(jwt.getClaimAsString(CurrentUser.SESSION_CLAIM));
        if (sessionId == null) {
            return OAuth2TokenValidatorResult.failure(INACTIVE_SESSION);
        }
        boolean active = switch (revocationStore.state(sessionId)) {
            case REVOKED -> false;
            case NOT_REVOKED -> true;
            case UNKNOWN -> sessions.existsByIdAndRevokedAtIsNull(sessionId);
        };
        return active ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(INACTIVE_SESSION);
    }

    private static UUID parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
