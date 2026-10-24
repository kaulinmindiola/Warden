package io.github.kaulinmindiola.warden.poc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * POC-001: ES256 access tokens are signed and verified with the resource server's Nimbus classes.
 *
 * <p>The issuer is read as a string: {@code Jwt#getIssuer()} converts it to a URL and fails for a
 * non-URL issuer such as {@code warden}.
 */
class JwtEs256RoundTripTest {

    @Test
    void signsWithEs256AndVerifiesWithTheResourceServerDecoder() throws Exception {
        ECKey signingKey = new ECKeyGenerator(Curve.P_256).keyID("poc-key").generate();

        String token = encode(signingKey, "user-1");
        Jwt decoded = decoderTrusting(signingKey.toPublicJWK()).decode(token);

        assertThat(decoded.getSubject()).isEqualTo("user-1");
        assertThat(decoded.getClaimAsString(JwtClaimNames.ISS)).isEqualTo("warden");
        assertThat(decoded.getHeaders()).containsEntry("alg", "ES256");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() throws Exception {
        ECKey signingKey = new ECKeyGenerator(Curve.P_256).keyID("poc-key").generate();
        ECKey otherKey = new ECKeyGenerator(Curve.P_256).keyID("other-key").generate();

        String token = encode(signingKey, "user-1");
        JwtDecoder decoder = decoderTrusting(otherKey.toPublicJWK());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private static String encode(ECKey signingKey, String subject) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("warden")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256)
                .keyId(signingKey.getKeyID())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static JwtDecoder decoderTrusting(ECKey publicKey) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, new ImmutableJWKSet<>(new JWKSet(publicKey))));
        return new NimbusJwtDecoder(processor);
    }
}
