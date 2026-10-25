package io.github.kaulinmindiola.warden.shared.crypto;

import java.time.Instant;
import java.util.Objects;

/**
 * A secret encrypted by {@link SecretCipher}, as it travels inside events and deliveries (SDD
 * V10: {@code Secret = {ciphertext, keyId, expiresAt}}).
 *
 * @param ciphertext Base64 of the 96-bit nonce followed by the AES-GCM ciphertext and tag
 * @param keyId id of the AES key that sealed it
 * @param expiresAt instant after which the secret can no longer be opened, in milliseconds
 */
public record SealedSecret(String ciphertext, String keyId, Instant expiresAt) {

    public SealedSecret {
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "SealedSecret[keyId=" + keyId + ", expiresAt=" + expiresAt + ", ciphertext redacted]";
    }
}
