package io.github.kaulinmindiola.warden.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class TokenHasherTest {

    private final TokenHasher hasher = new TokenHasher();

    @Test
    void matchesTheStandardSha256TestVector() {
        assertThat(HexFormat.of().formatHex(hasher.sha256("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void producesDifferent32ByteHashesForDifferentTokens() {
        byte[] first = hasher.sha256("wot_first");
        byte[] second = hasher.sha256("wot_second");

        assertThat(first).hasSize(32);
        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.sha256("wot_first")).isEqualTo(first);
    }
}
