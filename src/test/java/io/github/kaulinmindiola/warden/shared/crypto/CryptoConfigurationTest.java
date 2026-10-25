package io.github.kaulinmindiola.warden.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.shared.time.TimeConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The primitives are wired from the key material and the application clock. */
class CryptoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TimeConfiguration.class, KeyConfiguration.class, CryptoConfiguration.class)
            .withPropertyValues("warden.keys.allow-ephemeral=true");

    @Test
    void registersEveryPrimitive() {
        runner.run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(SecureTokenGenerator.class)
                    .hasSingleBean(TokenHasher.class)
                    .hasSingleBean(KeyedHasher.class)
                    .hasSingleBean(SecretCipher.class);

            SecretCipher cipher = context.getBean(SecretCipher.class);
            String token = context.getBean(SecureTokenGenerator.class).newOneTimeToken();
            SealedSecret sealed = cipher.seal(token, Instant.now().plus(Duration.ofHours(1)));

            assertThat(sealed.keyId())
                    .isEqualTo(context.getBean(KeyMaterial.class).secretKeys().currentKeyId());
            assertThat(cipher.open(sealed)).isEqualTo(token);
        });
    }
}
