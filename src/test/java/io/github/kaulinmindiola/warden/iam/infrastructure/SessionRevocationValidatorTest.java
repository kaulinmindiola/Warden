package io.github.kaulinmindiola.warden.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SessionRevocationValidatorTest {

    private final RevocationStore store = mock(RevocationStore.class);
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final SessionRevocationValidator validator = new SessionRevocationValidator(store, sessions);

    @Test
    void aRevokedSessionIsRejectedWithoutAskingPostgres() {
        UUID sessionId = UUID.randomUUID();
        when(store.state(sessionId)).thenReturn(RevocationStore.State.REVOKED);

        assertThat(validator.validate(jwt(sessionId.toString())).hasErrors()).isTrue();
        verify(sessions, never()).existsByIdAndRevokedAtIsNull(any());
    }

    @Test
    void aSessionTheStoreDoesNotKnowAsRevokedIsAcceptedWithoutAskingPostgres() {
        UUID sessionId = UUID.randomUUID();
        when(store.state(sessionId)).thenReturn(RevocationStore.State.NOT_REVOKED);

        assertThat(validator.validate(jwt(sessionId.toString())).hasErrors()).isFalse();
        verify(sessions, never()).existsByIdAndRevokedAtIsNull(any());
    }

    @Test
    void whenTheStoreCannotAnswerPostgresDecides() {
        UUID active = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();
        when(store.state(any())).thenReturn(RevocationStore.State.UNKNOWN);
        when(sessions.existsByIdAndRevokedAtIsNull(active)).thenReturn(true);
        when(sessions.existsByIdAndRevokedAtIsNull(revoked)).thenReturn(false);

        assertThat(validator.validate(jwt(active.toString())).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(revoked.toString())).hasErrors()).isTrue();
    }

    @Test
    void aMissingOrMalformedSessionIdIsRejected() {
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
        assertThat(validator.validate(jwt("not-a-uuid")).hasErrors()).isTrue();
    }

    private static Jwt jwt(String sessionId) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "ES256")
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.parse("2026-06-01T12:00:00Z"))
                .expiresAt(Instant.parse("2026-06-01T12:10:00Z"));
        if (sessionId != null) {
            builder.claim("sid", sessionId);
        }
        return builder.build();
    }
}
