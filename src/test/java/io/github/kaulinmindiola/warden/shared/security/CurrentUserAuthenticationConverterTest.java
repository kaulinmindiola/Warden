package io.github.kaulinmindiola.warden.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

class CurrentUserAuthenticationConverterTest {

    @Test
    void theAuthenticatedPrincipalIsTheCurrentUserWithoutAuthoritiesOrCredentials() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "ES256")
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .issuedAt(Instant.parse("2026-06-01T12:00:00Z"))
                .expiresAt(Instant.parse("2026-06-01T12:10:00Z"))
                .build();

        Authentication authentication = new CurrentUserAuthenticationConverter().convert(jwt);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(new CurrentUser(userId, sessionId));
        assertThat(authentication.getAuthorities()).isEmpty();
        assertThat(authentication.getCredentials()).isNull();
    }
}
