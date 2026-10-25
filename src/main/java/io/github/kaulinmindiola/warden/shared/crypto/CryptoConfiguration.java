package io.github.kaulinmindiola.warden.shared.crypto;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the cryptographic primitives, built from the key material and the clock. */
@Configuration(proxyBeanMethods = false)
public class CryptoConfiguration {

    @Bean
    public SecureTokenGenerator secureTokenGenerator() {
        return new SecureTokenGenerator(new SecureRandom());
    }

    @Bean
    public TokenHasher tokenHasher() {
        return new TokenHasher();
    }

    @Bean
    public KeyedHasher keyedHasher(KeyMaterial keyMaterial) {
        return new KeyedHasher(keyMaterial.hmacKey());
    }

    @Bean
    public SecretCipher secretCipher(KeyMaterial keyMaterial, Clock clock) {
        return new SecretCipher(keyMaterial.secretKeys(), clock, new SecureRandom());
    }
}
