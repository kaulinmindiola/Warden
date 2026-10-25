package io.github.kaulinmindiola.warden.shared.crypto;

import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Loads the key material at startup (REQ-INST-002, SDD V18). Startup fails with an explicit
 * message when a key is missing and ephemeral keys are not allowed, or when a key is invalid.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeyProperties.class)
public class KeyConfiguration {

    /** Profile that behaves like production: ephemeral keys are never allowed in it. */
    public static final String PROD_LIKE_PROFILE = "prod-like";

    private static final Logger log = LoggerFactory.getLogger(KeyConfiguration.class);

    @Bean
    public KeyMaterial keyMaterial(KeyProperties properties, Environment environment) {
        boolean ephemeralAllowed = properties.allowEphemeral() && !environment.matchesProfiles(PROD_LIKE_PROFILE);
        KeyMaterialLoader.Result result = new KeyMaterialLoader(new SecureRandom()).load(properties, ephemeralAllowed);
        if (!result.generated().isEmpty()) {
            log.warn(
                    "Generated ephemeral key material for {}; access tokens and sealed secrets will not survive a"
                            + " restart",
                    String.join(", ", result.generated()));
        }
        return result.keyMaterial();
    }
}
