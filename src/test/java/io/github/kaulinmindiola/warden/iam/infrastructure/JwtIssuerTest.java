package io.github.kaulinmindiola.warden.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.kaulinmindiola.warden.shared.crypto.SigningKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtIssuerTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00.987654Z");
    private static final JwtProperties PROPERTIES =
            new JwtProperties("warden", Duration.ofMinutes(10), Duration.ofSeconds(30));

    @Test
    void issuesExactlyTheSpecifiedClaimsAndHeader() throws Exception {
        KeyPair pair = ecKeyPair();
        JwtIssuer issuer = issuer(pair, "2026-01");
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        IssuedAccessToken token = issuer.issue(userId, sessionId);

        SignedJWT jwt = SignedJWT.parse(token.value());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("2026-01");
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "sid", "jti", "iat", "exp");
        assertThat(claims.getIssuer()).isEqualTo("warden");
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getStringClaim("sid")).isEqualTo(sessionId.toString());
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(Instant.parse("2026-06-01T12:00:00Z"));
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(Instant.parse("2026-06-01T12:10:00Z"));
        assertThat(token.expiresInSeconds()).isEqualTo(600);
        assertThat(issuer.issue(userId, sessionId).value()).isNotEqualTo(token.value());
    }

    @Test
    void theSignatureVerifiesWithThePublicKeyOnly() throws Exception {
        KeyPair pair = ecKeyPair();
        KeyPair other = ecKeyPair();

        SignedJWT jwt = SignedJWT.parse(
                issuer(pair, "k").issue(UUID.randomUUID(), UUID.randomUUID()).value());

        assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic())))
                .isTrue();
        assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) other.getPublic())))
                .isFalse();
    }

    private static JwtIssuer issuer(KeyPair pair, String keyId) {
        SigningKey key = new SigningKey(keyId, (ECPrivateKey) pair.getPrivate(), (ECPublicKey) pair.getPublic());
        return new JwtIssuer(key, PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
