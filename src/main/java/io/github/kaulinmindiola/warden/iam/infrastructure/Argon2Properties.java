package io.github.kaulinmindiola.warden.iam.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Argon2id parameters and the hashing concurrency limit (REQ-SEC-003, SDD Appendix E).
 *
 * @param memoryKib memory per hash in KiB (19456 = 19 MiB)
 * @param iterations number of passes
 * @param parallelism lanes per hash
 * @param maxConcurrency hashes allowed to run at the same time
 */
@ConfigurationProperties("warden.security.argon2")
public record Argon2Properties(
        @DefaultValue("19456") int memoryKib,
        @DefaultValue("2") int iterations,
        @DefaultValue("1") int parallelism,
        @DefaultValue("4") int maxConcurrency) {

    public Argon2Properties {
        if (memoryKib < 1024 || iterations < 1 || parallelism < 1 || maxConcurrency < 1) {
            throw new IllegalArgumentException(
                    "warden.security.argon2 needs memory-kib >= 1024 and iterations, parallelism and max-concurrency >= 1");
        }
    }
}
