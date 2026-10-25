package io.github.kaulinmindiola.warden.shared.crypto;

import java.util.Objects;
import javax.crypto.SecretKey;

/**
 * Every key of the application, loaded once at startup.
 *
 * @param signingKey ES256 key pair for access tokens
 * @param secretKeys AES-256 keys for secrets sealed in events and deliveries
 * @param hmacKey HMAC-SHA-256 key for keyed hashes of e-mail addresses and IPs
 */
public record KeyMaterial(SigningKey signingKey, SecretKeyRing secretKeys, SecretKey hmacKey) {

    public KeyMaterial {
        Objects.requireNonNull(signingKey, "signingKey");
        Objects.requireNonNull(secretKeys, "secretKeys");
        Objects.requireNonNull(hmacKey, "hmacKey");
    }

    @Override
    public String toString() {
        return "KeyMaterial[" + signingKey + ", " + secretKeys + ", hmacKey redacted]";
    }
}
