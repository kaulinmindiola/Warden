package io.github.kaulinmindiola.warden.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.github.kaulinmindiola.warden.iam.infrastructure.JwtIssuer;
import io.github.kaulinmindiola.warden.shared.crypto.KeyMaterial;
import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Access-token validation through the real filter chain (REQ-SEC-002, REQ-INT-002, REQ-COMP-002). */
class AccessTokenAuthenticationIT extends IntegrationTest {

    private static final String WHO_AM_I = ApiWebConfiguration.API_PREFIX + "/test/whoami";
    private static final String UNAUTHENTICATED = "urn:warden:problem:unauthenticated";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private KeyMaterial keyMaterial;

    @Autowired
    private JwtIssuer jwtIssuer;

    @Autowired
    private JsonMapper jsonMapper;

    private MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @Verifies({"REQ-SEC-002", "REQ-INT-002"})
    void aValidBearerTokenAuthenticatesTheCurrentUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        mvc.perform(get(WHO_AM_I)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtIssuer.issue(userId, sessionId).value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()));
    }

    @Test
    @Verifies("REQ-SEC-002")
    void forgedOrIncompleteTokensAreRejected() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ECPrivateKey foreignKey = (ECPrivateKey) foreignKeyPair().getPrivate();

        Map<String, String> forged = new LinkedHashMap<>();
        forged.put(
                "unsigned (alg=none)",
                new PlainJWT(validClaims(userId, sessionId).build()).serialize());
        forged.put(
                "HS256 keyed with the public key",
                hs256WithPublicKey(validClaims(userId, sessionId).build()));
        forged.put("signed by another key", es256(validClaims(userId, sessionId).build(), foreignKey));
        forged.put(
                "wrong issuer",
                es256(validClaims(userId, sessionId).issuer("not-warden").build(), ownKey()));
        forged.put(
                "no sid",
                es256(validClaims(userId, sessionId).claim("sid", null).build(), ownKey()));
        forged.put("no jti", es256(validClaims(userId, sessionId).jwtID(null).build(), ownKey()));
        forged.put(
                "sub is not a UUID",
                es256(validClaims(userId, sessionId).subject("admin").build(), ownKey()));
        forged.put("not a JWT", "abc.def.ghi");

        for (Map.Entry<String, String> token : forged.entrySet()) {
            int status = mvc.perform(get(WHO_AM_I).header(HttpHeaders.AUTHORIZATION, "Bearer " + token.getValue()))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as(token.getKey()).isEqualTo(401);
        }
        mvc.perform(get(WHO_AM_I).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged.get("wrong issuer")))
                .andExpect(jsonPath("$.type").value(UNAUTHENTICATED));
    }

    @Test
    @Verifies("REQ-SEC-002")
    void anExpiredTokenIsAcceptedFor30SecondsOnly() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant now = clock.instant();

        String expired29SecondsAgo = es256(
                validClaims(userId, sessionId)
                        .issueTime(Date.from(now.minusSeconds(629)))
                        .expirationTime(Date.from(now.minusSeconds(29)))
                        .build(),
                ownKey());
        String expired31SecondsAgo = es256(
                validClaims(userId, sessionId)
                        .issueTime(Date.from(now.minusSeconds(631)))
                        .expirationTime(Date.from(now.minusSeconds(31)))
                        .build(),
                ownKey());

        mvc.perform(get(WHO_AM_I).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired29SecondsAgo))
                .andExpect(status().isOk());
        mvc.perform(get(WHO_AM_I).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired31SecondsAgo))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Verifies("REQ-INT-002")
    void aTokenInTheQueryStringIsIgnored() throws Exception {
        String token = jwtIssuer.issue(UUID.randomUUID(), UUID.randomUUID()).value();

        mvc.perform(get(WHO_AM_I).queryParam("access_token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(UNAUTHENTICATED));
    }

    @Test
    @Verifies("REQ-COMP-002")
    void anIndependentVerifierValidatesIssuedTokensWithThePublicKey() throws Exception {
        UUID userId = UUID.randomUUID();
        String[] parts = jwtIssuer.issue(userId, UUID.randomUUID()).value().split("\\.");
        Base64.Decoder base64Url = Base64.getUrlDecoder();

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keyMaterial.signingKey().publicKey());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));

        @SuppressWarnings("unchecked")
        Map<String, Object> header = jsonMapper.readValue(base64Url.decode(parts[0]), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = jsonMapper.readValue(base64Url.decode(parts[1]), Map.class);
        assertThat(verifier.verify(base64Url.decode(parts[2]))).isTrue();
        assertThat(header)
                .containsEntry("alg", "ES256")
                .containsEntry("kid", keyMaterial.signingKey().keyId());
        assertThat(claims).containsEntry("sub", userId.toString()).containsEntry("iss", "warden");
    }

    private JWTClaimsSet.Builder validClaims(UUID userId, UUID sessionId) {
        Instant now = clock.instant();
        return new JWTClaimsSet.Builder()
                .issuer("warden")
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)));
    }

    private ECPrivateKey ownKey() {
        return keyMaterial.signingKey().privateKey();
    }

    private String es256(JWTClaimsSet claims, ECPrivateKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(keyMaterial.signingKey().keyId())
                        .build(),
                claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private String hs256WithPublicKey(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                        .keyID(keyMaterial.signingKey().keyId())
                        .build(),
                claims);
        jwt.sign(new MACSigner(keyMaterial.signingKey().publicKey().getEncoded()));
        return jwt.serialize();
    }

    private static java.security.KeyPair foreignKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** Registers the probe controller only in this test's context. */
    @TestConfiguration(proxyBeanMethods = false)
    @Import(WhoAmIController.class)
    static class WhoAmIConfiguration {}

    /** Protected probe endpoint that echoes the authenticated principal. */
    @TestComponent
    @RestController
    @RequestMapping("/test/whoami")
    static class WhoAmIController {

        @GetMapping
        Map<String, String> whoAmI(@AuthenticationPrincipal CurrentUser user) {
            return Map.of(
                    "userId", user.userId().toString(),
                    "sessionId", user.sessionId().toString());
        }
    }
}
