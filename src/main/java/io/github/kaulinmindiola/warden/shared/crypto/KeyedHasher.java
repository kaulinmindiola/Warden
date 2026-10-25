package io.github.kaulinmindiola.warden.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * HMAC-SHA-256 of low-entropy values such as normalized e-mail addresses and IPs, used as
 * rate-limit and lookup keys. Without the key, nobody can recompute the hash from a guessed
 * value (SDD V18). Callers normalize the value before hashing.
 */
public class KeyedHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKey key;

    public KeyedHasher(SecretKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /** HMAC-SHA-256 of the UTF-8 bytes of {@code value}, as 64 lowercase hexadecimal characters. */
    public String hmac(String value) {
        Objects.requireNonNull(value, "value");
        try {
            // A Mac instance is not thread-safe; one per call avoids sharing it between threads.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", exception);
        }
    }
}
