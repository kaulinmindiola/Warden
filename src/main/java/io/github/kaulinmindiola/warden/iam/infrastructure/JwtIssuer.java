package io.github.kaulinmindiola.warden.iam.infrastructure;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.github.kaulinmindiola.warden.shared.crypto.SigningKey;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs ES256 access tokens (REQ-INT-004, SDD V08). The claims are exactly {@code iss}, {@code
 * sub} (user id), {@code sid} (session id), {@code jti}, {@code iat} and {@code exp}; the header
 * carries {@code kid}. Tokens never contain roles, e-mail addresses or workspace data.
 */
public class JwtIssuer {

    /** Name of the session-id claim. */
    public static final String SESSION_CLAIM = "sid";

    private final JwtEncoder encoder;
    private final String keyId;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtIssuer(SigningKey signingKey, JwtProperties properties, Clock clock) {
        Objects.requireNonNull(signingKey, "signingKey");
        ECKey jwk = new ECKey.Builder(Curve.P_256, signingKey.publicKey())
                .privateKey(signingKey.privateKey())
                .keyID(signingKey.keyId())
                .build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        this.keyId = signingKey.keyId();
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Signs an access token for the user and session, valid for {@code warden.jwt.access-ttl}. */
    public IssuedAccessToken issue(UUID userId, UUID sessionId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(properties.accessTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .claim(SESSION_CLAIM, sessionId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keyId).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, expiresAt, properties.accessTtl().toSeconds());
    }
}
