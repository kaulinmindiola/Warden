package io.github.kaulinmindiola.warden.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Seals short secrets, such as one-time link tokens, with AES-256-GCM so they never travel or
 * rest in plaintext (REQ-SEC-005, SDD V18).
 *
 * <ul>
 *   <li>Each seal uses a fresh random 96-bit nonce, stored in front of the ciphertext.
 *   <li>The current key of the {@link SecretKeyRing} seals; any key still in the ring opens, so
 *       keys can be rotated.
 *   <li>The key id and the expiry are authenticated as associated data: changing either one
 *       makes opening fail.
 *   <li>The expiry is truncated to milliseconds so it survives a round trip through PostgreSQL.
 * </ul>
 */
public class SecretCipher {

    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String AAD_PREFIX = "warden-secret-v1|";

    private final SecretKeyRing keys;
    private final Clock clock;
    private final SecureRandom random;

    public SecretCipher(SecretKeyRing keys, Clock clock, SecureRandom random) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Seals {@code plaintext} with the current key.
     *
     * @throws IllegalArgumentException if {@code expiresAt} is not in the future
     */
    public SealedSecret seal(String plaintext, Instant expiresAt) {
        Objects.requireNonNull(plaintext, "plaintext");
        Instant expiry = Objects.requireNonNull(expiresAt, "expiresAt").truncatedTo(ChronoUnit.MILLIS);
        if (!clock.instant().isBefore(expiry)) {
            throw new IllegalArgumentException("A secret must expire in the future");
        }
        String keyId = keys.currentKeyId();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);

        byte[] encrypted = crypt(
                Cipher.ENCRYPT_MODE, keys.current(), nonce, keyId, expiry, plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] output = ByteBuffer.allocate(NONCE_BYTES + encrypted.length)
                .put(nonce)
                .put(encrypted)
                .array();
        return new SealedSecret(Base64.getEncoder().encodeToString(output), keyId, expiry);
    }

    /**
     * Opens a sealed secret.
     *
     * @throws SecretCipherException if the key is unknown, the secret has expired or anything was
     *     altered
     */
    public String open(SealedSecret sealed) {
        Objects.requireNonNull(sealed, "sealed");
        SecretKey key = keys.find(sealed.keyId())
                .orElseThrow(() -> new SecretCipherException(
                        SecretCipherException.Reason.UNKNOWN_KEY, "Unknown secret key id: " + sealed.keyId()));
        if (!clock.instant().isBefore(sealed.expiresAt())) {
            throw new SecretCipherException(SecretCipherException.Reason.EXPIRED, "The sealed secret has expired");
        }
        byte[] input;
        try {
            input = Base64.getDecoder().decode(sealed.ciphertext());
        } catch (IllegalArgumentException exception) {
            throw tampered();
        }
        if (input.length < NONCE_BYTES + TAG_BITS / 8) {
            throw tampered();
        }
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(input, 0, nonce, 0, NONCE_BYTES);
        byte[] encrypted = new byte[input.length - NONCE_BYTES];
        System.arraycopy(input, NONCE_BYTES, encrypted, 0, encrypted.length);

        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, key, nonce, sealed.keyId(), sealed.expiresAt(), encrypted);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static byte[] crypt(int mode, SecretKey key, byte[] nonce, String keyId, Instant expiry, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD((AAD_PREFIX + keyId + "|" + expiry.toEpochMilli()).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(data);
        } catch (AEADBadTagException exception) {
            throw tampered();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM failed", exception);
        }
    }

    private static SecretCipherException tampered() {
        return new SecretCipherException(
                SecretCipherException.Reason.TAMPERED, "The sealed secret failed authentication");
    }
}
