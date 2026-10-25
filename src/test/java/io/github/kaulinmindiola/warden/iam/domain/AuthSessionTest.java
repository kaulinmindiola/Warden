package io.github.kaulinmindiola.warden.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthSessionTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void aSessionIsActiveUntilItsAbsoluteExpiryAfter30Days() {
        AuthSession session = AuthSession.start(UUID.randomUUID(), UUID.randomUUID(), NOW, "Mozilla/5.0");

        assertThat(session.absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(session.lastUsedAt()).isEqualTo(NOW);
        assertThat(session.isActive(NOW.plus(Duration.ofDays(30)).minusSeconds(1)))
                .isTrue();
        assertThat(session.isActive(NOW.plus(Duration.ofDays(30)))).isFalse();
    }

    @Test
    void aRevokedSessionStaysRevokedWithItsFirstReason() {
        AuthSession session = AuthSession.start(UUID.randomUUID(), UUID.randomUUID(), NOW, null);

        session.revoke(AuthSession.RevokeReason.LOGOUT, NOW.plusSeconds(10));
        session.revoke(AuthSession.RevokeReason.REUSE, NOW.plusSeconds(20));

        assertThat(session.isActive(NOW.plusSeconds(11))).isFalse();
        assertThat(session.revokedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(session.revokeReason()).isEqualTo(AuthSession.RevokeReason.LOGOUT);
    }

    @Test
    void theUserAgentSummaryIsSanitizedAndLimited() {
        assertThat(AuthSession.summarizeUserAgent(null)).isNull();
        assertThat(AuthSession.summarizeUserAgent(" \t\r\n ")).isNull();
        assertThat(AuthSession.summarizeUserAgent("Mozilla\r\n/5.0\u0007 ")).isEqualTo("Mozilla/5.0");
        assertThat(AuthSession.summarizeUserAgent("a".repeat(500))).hasSize(200);
        assertThat(AuthSession.summarizeUserAgent("\uD83D\uDE00".repeat(300)).codePointCount(0, 400))
                .isEqualTo(200);
    }
}
