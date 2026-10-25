package io.github.kaulinmindiola.warden.iam.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.github.kaulinmindiola.warden.shared.crypto.SigningKey;
import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the strict access-token decoder (REQ-SEC-002, SDD V18).
 *
 * <ul>
 *   <li>Only ES256 signatures made with the configured key are accepted; unsigned tokens, other
 *       algorithms (including HS256 keyed with the public key) and foreign signatures are rejected.
 *   <li>{@code exp} and {@code nbf} are checked with the configured clock skew (30 s) against the
 *       application clock.
 *   <li>{@code iss} must equal the configured issuer; {@code sub} and {@code sid} must be UUIDs;
 *       {@code jti}, {@code iat} and {@code exp} must be present.
 * </ul>
 */
public final class AccessTokenDecoder {

    private AccessTokenDecoder() {}

    public static JwtDecoder create(SigningKey signingKey, JwtProperties properties, Clock clock) {
        ECKey verificationKey = new ECKey.Builder(Curve.P_256, signingKey.publicKey())
                .keyID(signingKey.keyId())
                .build();
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.ES256, new ImmutableJWKSet<>(new JWKSet(verificationKey))));
        // Claims are validated by the Spring validators below, with the configured clock skew.
        processor.setJWTClaimsSetVerifier((claims, context) -> {});

        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.clockSkew());
        timestamps.setClock(clock);

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                claim(JwtClaimNames.ISS, value -> properties.issuer().equals(value)),
                claim(JwtClaimNames.SUB, AccessTokenDecoder::isUuid),
                claim(CurrentUser.SESSION_CLAIM, AccessTokenDecoder::isUuid),
                claim(JwtClaimNames.JTI, value -> value instanceof String jti && !jti.isBlank()),
                claim(JwtClaimNames.IAT, Objects::nonNull),
                claim(JwtClaimNames.EXP, Objects::nonNull)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> claim(String name, Predicate<Object> test) {
        return new JwtClaimValidator<>(name, test);
    }

    private static boolean isUuid(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            UUID.fromString(text);
            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }
}
