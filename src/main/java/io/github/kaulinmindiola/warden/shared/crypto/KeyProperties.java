package io.github.kaulinmindiola.warden.shared.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Raw key configuration bound from {@code warden.keys.*}, which {@code application.yaml} maps to
 * the {@code WARDEN_*} environment variables.
 *
 * @param jwtSigningKey ES256 (P-256) private key as PKCS#8, PEM or bare Base64
 * @param jwtKeyId key id ({@code kid}) published in the header of every access token
 * @param secretKeys AES-256 keys as {@code id:base64key[,id:base64key]}; the first one seals
 * @param hmacKey Base64 key of at least 32 bytes for HMAC-SHA-256
 * @param allowEphemeral whether missing keys may be generated at startup
 */
@ConfigurationProperties("warden.keys")
public record KeyProperties(
        String jwtSigningKey, String jwtKeyId, String secretKeys, String hmacKey, boolean allowEphemeral) {

    @Override
    public String toString() {
        return "KeyProperties[values redacted, allowEphemeral=" + allowEphemeral + "]";
    }
}
