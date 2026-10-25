package io.github.kaulinmindiola.warden.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecureTokenGeneratorTest {

    private final SecureTokenGenerator generator = new SecureTokenGenerator(new SecureRandom());

    @Test
    @Verifies("REQ-SEC-004")
    void refreshTokensHaveThePrefixAnd256RandomBits() {
        String token = generator.newRefreshToken();

        assertThat(token).startsWith("wrt_").hasSize(47).matches("wrt_[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(token.substring(4))).hasSize(32);
    }

    @Test
    @Verifies("REQ-SEC-004")
    void oneTimeTokensHaveThePrefixAnd256RandomBits() {
        String token = generator.newOneTimeToken();

        assertThat(token).startsWith("wot_").hasSize(47).matches("wot_[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(token.substring(4))).hasSize(32);
    }

    @Test
    void tokensDoNotRepeat() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            tokens.add(generator.newOneTimeToken());
        }

        assertThat(tokens).hasSize(1_000);
    }
}
