package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.shared.crypto.KeyMaterial;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Access-token issuing and validation with the ES256 signing key of the key material. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class TokenConfiguration {

    @Bean
    public JwtIssuer jwtIssuer(KeyMaterial keyMaterial, JwtProperties properties, Clock clock) {
        return new JwtIssuer(keyMaterial.signingKey(), properties, clock);
    }

    /** Used by the security filter chain to authenticate every request (REQ-SEC-002). */
    @Bean
    public JwtDecoder accessTokenDecoder(KeyMaterial keyMaterial, JwtProperties properties, Clock clock) {
        return AccessTokenDecoder.create(keyMaterial.signingKey(), properties, clock);
    }
}
