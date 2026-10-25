package io.github.kaulinmindiola.warden.shared.crypto;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Objects;

/**
 * ES256 (P-256) key pair that signs access tokens, identified by its {@code kid}.
 *
 * @param keyId key id written in the JWT header
 * @param privateKey signing key
 * @param publicKey verification key derived from the private key
 */
public record SigningKey(String keyId, ECPrivateKey privateKey, ECPublicKey publicKey) {

    public SigningKey {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(publicKey, "publicKey");
    }

    @Override
    public String toString() {
        return "SigningKey[keyId=" + keyId + ", key material redacted]";
    }
}
