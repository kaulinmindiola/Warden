package io.github.kaulinmindiola.warden.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class KeyMaterialLoaderTest {

    private static final String AES_KEY_1 = base64(32, (byte) 1);
    private static final String AES_KEY_2 = base64(32, (byte) 2);
    private static final String HMAC_KEY = base64(32, (byte) 3);

    private final KeyMaterialLoader loader = new KeyMaterialLoader(new SecureRandom());

    @Test
    void loadsConfiguredKeysAndDerivesTheMatchingPublicKey() throws Exception {
        KeyPair pair = ecKeyPair("secp256r1");
        KeyProperties properties =
                new KeyProperties(pem(pair), "2026-01", "k2:" + AES_KEY_2 + ",k1:" + AES_KEY_1, HMAC_KEY, false);

        KeyMaterialLoader.Result result = loader.load(properties, false);

        KeyMaterial keys = result.keyMaterial();
        assertThat(result.generated()).isEmpty();
        assertThat(keys.signingKey().keyId()).isEqualTo("2026-01");
        assertThat(keys.signingKey().publicKey().getW())
                .isEqualTo(((java.security.interfaces.ECPublicKey) pair.getPublic()).getW());
        assertThat(signAndVerify(keys.signingKey())).isTrue();
        assertThat(keys.secretKeys().currentKeyId()).isEqualTo("k2");
        assertThat(keys.secretKeys().keyIds()).containsExactly("k2", "k1");
        assertThat(keys.secretKeys().find("k1")).isPresent();
        assertThat(keys.secretKeys().find("unknown")).isEmpty();
        assertThat(keys.hmacKey().getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void acceptsAPemWithEscapedNewlinesAsInAnEnvironmentVariable() throws Exception {
        KeyPair pair = ecKeyPair("secp256r1");
        String escaped = pem(pair).replace("\n", "\\n");

        KeyMaterial keys = loader.load(new KeyProperties(escaped, "k", "k1:" + AES_KEY_1, HMAC_KEY, false), false)
                .keyMaterial();

        assertThat(signAndVerify(keys.signingKey())).isTrue();
    }

    @Test
    void generatesEveryMissingKeyWhenEphemeralKeysAreAllowed() throws Exception {
        KeyMaterialLoader.Result result = loader.load(new KeyProperties(null, null, null, null, true), true);

        assertThat(result.generated())
                .containsExactly(
                        "WARDEN_JWT_SIGNING_KEY", "WARDEN_JWT_KEY_ID", "WARDEN_SECRET_KEYS", "WARDEN_HMAC_KEY");
        assertThat(result.keyMaterial().signingKey().keyId()).startsWith("ephemeral-");
        assertThat(signAndVerify(result.keyMaterial().signingKey())).isTrue();
        assertThat(result.keyMaterial().secretKeys().current().getEncoded()).hasSize(32);
        assertThat(result.keyMaterial().hmacKey().getEncoded()).hasSize(32);
    }

    @Test
    void generatesOnlyTheKeysThatAreMissing() {
        KeyMaterialLoader.Result result = loader.load(new KeyProperties(null, null, "k1:" + AES_KEY_1, "", true), true);

        assertThat(result.generated())
                .containsExactly("WARDEN_JWT_SIGNING_KEY", "WARDEN_JWT_KEY_ID", "WARDEN_HMAC_KEY");
        assertThat(result.keyMaterial().secretKeys().currentKeyId()).isEqualTo("k1");
    }

    @Test
    void failsNamingEveryMissingVariableWhenEphemeralKeysAreNotAllowed() {
        assertThatThrownBy(() -> loader.load(new KeyProperties(" ", null, null, null, true), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing key material: WARDEN_JWT_SIGNING_KEY, WARDEN_JWT_KEY_ID, WARDEN_SECRET_KEYS,"
                        + " WARDEN_HMAC_KEY. Provide these environment variables; ephemeral keys are generated only"
                        + " in the local profile.");
    }

    @Test
    void requiresTheSigningKeyAndItsKeyIdTogether() throws Exception {
        KeyProperties properties =
                new KeyProperties(pem(ecKeyPair("secp256r1")), null, "k1:" + AES_KEY_1, HMAC_KEY, true);

        assertThatThrownBy(() -> loader.load(properties, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be provided together");
    }

    @Test
    void rejectsASigningKeyOnAnotherCurve() throws Exception {
        KeyProperties properties =
                new KeyProperties(pem(ecKeyPair("secp384r1")), "k", "k1:" + AES_KEY_1, HMAC_KEY, false);

        assertThatThrownBy(() -> loader.load(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WARDEN_JWT_SIGNING_KEY must be a P-256 (secp256r1) key");
    }

    @Test
    void rejectsASigningKeyThatIsNotPkcs8() {
        KeyProperties properties = new KeyProperties(
                "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----",
                "k",
                "k1:" + AES_KEY_1,
                HMAC_KEY,
                false);

        assertThatThrownBy(() -> loader.load(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    void rejectsSecretKeysOfTheWrongLengthWithoutRevealingThem() throws Exception {
        String shortKey = base64(16, (byte) 7);
        KeyProperties properties =
                new KeyProperties(pem(ecKeyPair("secp256r1")), "k", "k1:" + shortKey, HMAC_KEY, false);

        assertThatThrownBy(() -> loader.load(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WARDEN_SECRET_KEYS key id k1 must be 32 bytes (AES-256), but is 16")
                .message()
                .doesNotContain(shortKey);
    }

    @Test
    void rejectsRepeatedSecretKeyIds() throws Exception {
        KeyProperties properties = new KeyProperties(
                pem(ecKeyPair("secp256r1")), "k", "k1:" + AES_KEY_1 + ",k1:" + AES_KEY_2, HMAC_KEY, false);

        assertThatThrownBy(() -> loader.load(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WARDEN_SECRET_KEYS repeats key id k1");
    }

    @Test
    void rejectsMalformedSecretKeys() throws Exception {
        KeyProperties properties = new KeyProperties(pem(ecKeyPair("secp256r1")), "k", AES_KEY_1, HMAC_KEY, false);

        assertThatThrownBy(() -> loader.load(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WARDEN_SECRET_KEYS must have the form id:base64key[,id:base64key]");
    }

    @Test
    void rejectsAShortHmacKey() throws Exception {
        KeyProperties properties =
                new KeyProperties(pem(ecKeyPair("secp256r1")), "k", "k1:" + AES_KEY_1, base64(31, (byte) 4), false);

        assertThatThrownBy(() -> loader.load(properties, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WARDEN_HMAC_KEY must be at least 32 bytes, but is 31");
    }

    @Test
    void textualFormsNeverContainKeyMaterial() throws Exception {
        KeyPair pair = ecKeyPair("secp256r1");
        String signingKey = pem(pair);
        KeyProperties properties = new KeyProperties(signingKey, "k", "k1:" + AES_KEY_1, HMAC_KEY, false);

        KeyMaterial keys = loader.load(properties, false).keyMaterial();

        assertThat(keys.toString())
                .contains("keyId=k", "currentKeyId=k1")
                .doesNotContain(
                        AES_KEY_1,
                        HMAC_KEY,
                        keys.signingKey().privateKey().getS().toString());
        assertThat(properties.toString()).doesNotContain(AES_KEY_1, HMAC_KEY, "PRIVATE KEY");
    }

    private static boolean signAndVerify(SigningKey key) throws Exception {
        byte[] data = "warden".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.privateKey());
        signer.update(data);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key.publicKey());
        verifier.update(data);
        return verifier.verify(signature);
    }

    private static KeyPair ecKeyPair(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    private static String pem(KeyPair pair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static String base64(int length, byte value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
