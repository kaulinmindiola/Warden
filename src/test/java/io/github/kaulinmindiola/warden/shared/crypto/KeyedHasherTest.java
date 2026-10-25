package io.github.kaulinmindiola.warden.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class KeyedHasherTest {

    @Test
    void matchesTheRfc4231TestVector() {
        KeyedHasher hasher =
                new KeyedHasher(new SecretKeySpec("Jefe".getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));

        assertThat(hasher.hmac("what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void isDeterministicPerKeyAndDiffersBetweenKeys() {
        KeyedHasher first = new KeyedHasher(key((byte) 1));
        KeyedHasher second = new KeyedHasher(key((byte) 2));

        assertThat(first.hmac("ana@mail.com"))
                .isEqualTo(first.hmac("ana@mail.com"))
                .hasSize(64);
        assertThat(first.hmac("ana@mail.com")).isNotEqualTo(first.hmac("bob@mail.com"));
        assertThat(first.hmac("ana@mail.com")).isNotEqualTo(second.hmac("ana@mail.com"));
    }

    private static SecretKeySpec key(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
