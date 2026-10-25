package io.github.kaulinmindiola.warden.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * SHA-256 of high-entropy tokens (REQ-SEC-004). A plain hash is enough because a 256-bit token
 * cannot be guessed; low-entropy values use {@link KeyedHasher} instead.
 */
public class TokenHasher {

    /** SHA-256 of the UTF-8 bytes of {@code token}: always 32 bytes. */
    public byte[] sha256(String token) {
        Objects.requireNonNull(token, "token");
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
