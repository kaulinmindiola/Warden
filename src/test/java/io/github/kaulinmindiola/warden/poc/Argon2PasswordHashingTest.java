package io.github.kaulinmindiola.warden.poc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** POC-001: Argon2id through Spring Security and Bouncy Castle with m=19 MiB, t=2, p=1. */
class Argon2PasswordHashingTest {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;

    private final Argon2PasswordEncoder encoder =
            new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);

    @Test
    void hashesWithTheConfiguredParametersAndVerifies() {
        String hash = encoder.encode("correct horse battery staple");

        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(encoder.matches("correct horse battery staple", hash)).isTrue();
    }

    @Test
    void rejectsAWrongPassword() {
        String hash = encoder.encode("correct horse battery staple");

        assertThat(encoder.matches("wrong password", hash)).isFalse();
    }
}
