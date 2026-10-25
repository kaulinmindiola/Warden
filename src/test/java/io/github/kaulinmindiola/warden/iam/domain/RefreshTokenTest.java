package io.github.kaulinmindiola.warden.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void aRefreshTokenExpiresAfter7Days() {
        AuthSession session = AuthSession.start(UUID.randomUUID(), UUID.randomUUID(), NOW, null);

        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), session, hash(), NOW);

        assertThat(token.sessionId()).isEqualTo(session.id());
        assertThat(token.issuedAt()).isEqualTo(NOW);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(token.usedAt()).isNull();
    }

    @Test
    void aRefreshTokenNeverOutlivesItsSession() {
        AuthSession session = AuthSession.start(UUID.randomUUID(), UUID.randomUUID(), NOW, null);
        Instant lateRefresh = NOW.plus(Duration.ofDays(26));

        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), session, hash(), lateRefresh);

        assertThat(token.expiresAt()).isEqualTo(session.absoluteExpiresAt());
    }

    private static TokenHash hash() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 5);
        return TokenHash.of(bytes);
    }
}
