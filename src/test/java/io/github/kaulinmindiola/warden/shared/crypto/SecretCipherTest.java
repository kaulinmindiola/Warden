package io.github.kaulinmindiola.warden.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant EXPIRY = NOW.plus(Duration.ofHours(24));
    private static final String TOKEN = "wot_dGhpcyBpcyBhIG9uZS10aW1lIHRva2VuIGZvciB0ZXN0cw";

    private static final SecretKey KEY_1 = aesKey((byte) 1);
    private static final SecretKey KEY_2 = aesKey((byte) 2);

    private final SecretCipher cipher = cipher(ring("k1", Map.of("k1", KEY_1)), NOW);

    @Test
    @Verifies("REQ-SEC-005")
    void roundTripsASecret() {
        SealedSecret sealed = cipher.seal(TOKEN, EXPIRY);

        assertThat(sealed.keyId()).isEqualTo("k1");
        assertThat(sealed.expiresAt()).isEqualTo(EXPIRY);
        assertThat(sealed.ciphertext()).doesNotContain(TOKEN);
        assertThat(Base64.getDecoder().decode(sealed.ciphertext()))
                .hasSize(SecretCipher.NONCE_BYTES + TOKEN.length() + SecretCipher.TAG_BITS / 8);
        assertThat(cipher.open(sealed)).isEqualTo(TOKEN);
    }

    @Test
    void usesAFreshNonceForEverySeal() {
        assertThat(cipher.seal(TOKEN, EXPIRY).ciphertext())
                .isNotEqualTo(cipher.seal(TOKEN, EXPIRY).ciphertext());
    }

    @Test
    void truncatesTheExpiryToMilliseconds() {
        SealedSecret sealed = cipher.seal(TOKEN, EXPIRY.plusNanos(123_456_789));

        assertThat(sealed.expiresAt()).isEqualTo(EXPIRY.plusMillis(123));
        assertThat(cipher.open(sealed)).isEqualTo(TOKEN);
    }

    @Test
    @Verifies("REQ-SEC-005")
    void rejectsAnAlteredCiphertext() {
        SealedSecret sealed = cipher.seal(TOKEN, EXPIRY);
        byte[] bytes = Base64.getDecoder().decode(sealed.ciphertext());
        bytes[bytes.length - 1] ^= 0x01;
        SealedSecret altered =
                new SealedSecret(Base64.getEncoder().encodeToString(bytes), sealed.keyId(), sealed.expiresAt());

        assertReason(() -> cipher.open(altered), SecretCipherException.Reason.TAMPERED);
    }

    @Test
    void rejectsAnExtendedExpiry() {
        SealedSecret sealed = cipher.seal(TOKEN, EXPIRY);
        SealedSecret extended = new SealedSecret(
                sealed.ciphertext(), sealed.keyId(), sealed.expiresAt().plus(Duration.ofDays(7)));

        assertReason(() -> cipher.open(extended), SecretCipherException.Reason.TAMPERED);
    }

    @Test
    void rejectsASecretRelabelledWithAnotherKnownKeyId() {
        SecretCipher twoKeys = cipher(ring("k1", ordered("k1", KEY_1, "k2", KEY_2)), NOW);
        SealedSecret sealed = twoKeys.seal(TOKEN, EXPIRY);
        SealedSecret relabelled = new SealedSecret(sealed.ciphertext(), "k2", sealed.expiresAt());

        assertReason(() -> twoKeys.open(relabelled), SecretCipherException.Reason.TAMPERED);
    }

    @Test
    void rejectsMalformedCiphertexts() {
        assertReason(
                () -> cipher.open(new SealedSecret("not base64!", "k1", EXPIRY)),
                SecretCipherException.Reason.TAMPERED);
        assertReason(
                () -> cipher.open(new SealedSecret(Base64.getEncoder().encodeToString(new byte[10]), "k1", EXPIRY)),
                SecretCipherException.Reason.TAMPERED);
    }

    @Test
    @Verifies("REQ-SEC-005")
    void rejectsAnUnknownKeyId() {
        SealedSecret sealed = cipher.seal(TOKEN, EXPIRY);
        SecretCipher otherRing = cipher(ring("k2", Map.of("k2", KEY_2)), NOW);

        assertReason(() -> otherRing.open(sealed), SecretCipherException.Reason.UNKNOWN_KEY);
    }

    @Test
    void rejectsAnExpiredSecret() {
        SealedSecret sealed = cipher.seal(TOKEN, EXPIRY);

        assertThat(cipher(ring("k1", Map.of("k1", KEY_1)), EXPIRY.minusMillis(1))
                        .open(sealed))
                .isEqualTo(TOKEN);
        assertReason(
                () -> cipher(ring("k1", Map.of("k1", KEY_1)), EXPIRY).open(sealed),
                SecretCipherException.Reason.EXPIRED);
    }

    @Test
    void refusesToSealASecretThatIsAlreadyExpired() {
        assertThatThrownBy(() -> cipher.seal(TOKEN, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void opensSecretsSealedBeforeAKeyRotation() {
        SealedSecret beforeRotation = cipher.seal(TOKEN, EXPIRY);
        SecretCipher rotated = cipher(ring("k2", ordered("k2", KEY_2, "k1", KEY_1)), NOW);

        SealedSecret afterRotation = rotated.seal(TOKEN, EXPIRY);

        assertThat(rotated.open(beforeRotation)).isEqualTo(TOKEN);
        assertThat(afterRotation.keyId()).isEqualTo("k2");
        assertThat(rotated.open(afterRotation)).isEqualTo(TOKEN);
    }

    @Test
    void neverRevealsTheSecretInTextualForms() {
        SealedSecret sealed = cipher.seal(TOKEN, EXPIRY);

        assertThat(sealed.toString()).doesNotContain(sealed.ciphertext(), TOKEN);
        assertThatThrownBy(() -> cipher(ring("k1", Map.of("k1", KEY_1)), EXPIRY).open(sealed))
                .message()
                .doesNotContain(TOKEN, sealed.ciphertext());
    }

    private static void assertReason(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, SecretCipherException.Reason reason) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        SecretCipherException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
    }

    private static SecretCipher cipher(SecretKeyRing ring, Instant now) {
        return new SecretCipher(ring, Clock.fixed(now, ZoneOffset.UTC), new SecureRandom());
    }

    private static SecretKeyRing ring(String currentKeyId, Map<String, SecretKey> keys) {
        return new SecretKeyRing(currentKeyId, keys);
    }

    private static Map<String, SecretKey> ordered(String id1, SecretKey key1, String id2, SecretKey key2) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        keys.put(id1, key1);
        keys.put(id2, key2);
        return keys;
    }

    private static SecretKey aesKey(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return new SecretKeySpec(bytes, "AES");
    }
}
