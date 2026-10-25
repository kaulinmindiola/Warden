package io.github.kaulinmindiola.warden.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OneTimeTokenTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final TokenHash HASH = TokenHash.of(filled((byte) 7));

    @Test
    void aVerificationTokenIsValidFor24Hours() {
        OneTimeToken token = issue(OneTimeToken.Purpose.EMAIL_VERIFICATION);

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(token.isActive(NOW.plus(Duration.ofHours(24)).minusSeconds(1)))
                .isTrue();
        assertThat(token.isActive(NOW.plus(Duration.ofHours(24)).plusSeconds(1)))
                .isFalse();
    }

    @Test
    void aPasswordResetTokenIsValidFor30Minutes() {
        OneTimeToken token = issue(OneTimeToken.Purpose.PASSWORD_RESET);

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(token.isActive(NOW.plus(Duration.ofMinutes(30)))).isFalse();
    }

    @Test
    void aTokenCanBeConsumedOnlyOnce() {
        OneTimeToken token = issue(OneTimeToken.Purpose.EMAIL_VERIFICATION);

        token.consume(NOW.plusSeconds(10));

        assertThat(token.consumedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(token.isActive(NOW.plusSeconds(11))).isFalse();
        assertThatThrownBy(() -> token.consume(NOW.plusSeconds(20))).isInstanceOf(InvalidOneTimeTokenException.class);
    }

    @Test
    void anInvalidatedTokenCannotBeConsumed() {
        OneTimeToken token = issue(OneTimeToken.Purpose.EMAIL_VERIFICATION);

        token.invalidate(NOW.plusSeconds(5));

        assertThat(token.invalidatedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThatThrownBy(() -> token.consume(NOW.plusSeconds(6))).isInstanceOf(InvalidOneTimeTokenException.class);
    }

    @Test
    void anExpiredTokenCannotBeConsumedAndInvalidatingAUsedTokenChangesNothing() {
        OneTimeToken expired = issue(OneTimeToken.Purpose.PASSWORD_RESET);
        OneTimeToken used = issue(OneTimeToken.Purpose.PASSWORD_RESET);
        used.consume(NOW.plusSeconds(1));

        assertThatThrownBy(() -> expired.consume(NOW.plus(Duration.ofMinutes(31))))
                .isInstanceOf(InvalidOneTimeTokenException.class)
                .hasMessage("The one-time token is not valid");
        used.invalidate(NOW.plusSeconds(2));
        assertThat(used.invalidatedAt()).isNull();
    }

    @Test
    void aTokenHashHas32BytesAndIsCopied() {
        byte[] bytes = filled((byte) 1);
        TokenHash hash = TokenHash.of(bytes);
        bytes[0] = 9;

        assertThat(hash.bytes()[0]).isEqualTo((byte) 1);
        assertThat(hash).isEqualTo(TokenHash.of(filled((byte) 1))).isNotEqualTo(HASH);
        assertThat(hash.toString()).isEqualTo("TokenHash[01010101...]");
        assertThatThrownBy(() -> TokenHash.of(new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }

    private static OneTimeToken issue(OneTimeToken.Purpose purpose) {
        return OneTimeToken.issue(UUID.randomUUID(), UUID.randomUUID(), purpose, HASH, NOW);
    }

    private static byte[] filled(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
