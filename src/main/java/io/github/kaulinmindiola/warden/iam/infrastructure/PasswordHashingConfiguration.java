package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.PasswordHasher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Argon2id password hashing (REQ-SEC-003) with a bounded number of concurrent computations. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Argon2Properties.class)
public class PasswordHashingConfiguration {

    /** Salt length in bytes. */
    static final int SALT_LENGTH = 16;

    /** Hash length in bytes. */
    static final int HASH_LENGTH = 32;

    @Bean
    public PasswordHasher passwordHasher(Argon2Properties properties) {
        Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(
                SALT_LENGTH, HASH_LENGTH, properties.parallelism(), properties.memoryKib(), properties.iterations());
        return new BoundedPasswordHasher(encoder, properties.maxConcurrency());
    }
}
