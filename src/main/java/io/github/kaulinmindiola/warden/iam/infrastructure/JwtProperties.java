package io.github.kaulinmindiola.warden.iam.infrastructure;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token settings (SDD Appendix E).
 *
 * @param issuer value of the {@code iss} claim
 * @param accessTtl lifetime of an access token (REQ-INT-004: 10 minutes)
 * @param clockSkew tolerance when validating {@code exp} (REQ-SEC-002: 30 seconds)
 */
@ConfigurationProperties("warden.jwt")
public record JwtProperties(
        @DefaultValue("warden") String issuer,
        @DefaultValue("10m") Duration accessTtl,
        @DefaultValue("30s") Duration clockSkew) {

    public JwtProperties {
        Objects.requireNonNull(issuer, "issuer");
        if (issuer.isBlank() || accessTtl.isNegative() || accessTtl.isZero() || clockSkew.isNegative()) {
            throw new IllegalArgumentException(
                    "warden.jwt needs a non-blank issuer, a positive access-ttl and a non-negative clock-skew");
        }
    }
}
