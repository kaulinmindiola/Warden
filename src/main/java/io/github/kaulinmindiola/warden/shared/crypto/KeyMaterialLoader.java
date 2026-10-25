package io.github.kaulinmindiola.warden.shared.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

/**
 * Builds {@link KeyMaterial} from {@link KeyProperties}: parses the configured keys and, when
 * allowed, generates the missing ones. Error messages name the environment variable and the
 * problem, never the value.
 */
final class KeyMaterialLoader {

    static final String SIGNING_KEY_VARIABLE = "WARDEN_JWT_SIGNING_KEY";
    static final String KEY_ID_VARIABLE = "WARDEN_JWT_KEY_ID";
    static final String SECRET_KEYS_VARIABLE = "WARDEN_SECRET_KEYS";
    static final String HMAC_KEY_VARIABLE = "WARDEN_HMAC_KEY";

    static final int AES_KEY_BYTES = 32;
    static final int MIN_HMAC_KEY_BYTES = 32;

    private static final String CURVE = "secp256r1";
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final SecureRandom random;

    KeyMaterialLoader(SecureRandom random) {
        this.random = random;
    }

    /** Result of loading: the keys and the names of the variables that were generated. */
    record Result(KeyMaterial keyMaterial, List<String> generated) {}

    /**
     * Loads the key material.
     *
     * @param properties configured values
     * @param ephemeralAllowed whether missing keys may be generated
     * @throws IllegalStateException if a key is missing and cannot be generated, or is invalid
     */
    Result load(KeyProperties properties, boolean ephemeralAllowed) {
        boolean hasSigningKey = hasText(properties.jwtSigningKey());
        boolean hasKeyId = hasText(properties.jwtKeyId());
        boolean hasSecretKeys = hasText(properties.secretKeys());
        boolean hasHmacKey = hasText(properties.hmacKey());

        if (hasSigningKey != hasKeyId) {
            throw new IllegalStateException(SIGNING_KEY_VARIABLE + " and " + KEY_ID_VARIABLE
                    + " must be provided together; only " + (hasSigningKey ? SIGNING_KEY_VARIABLE : KEY_ID_VARIABLE)
                    + " is set");
        }

        List<String> missing = new ArrayList<>();
        if (!hasSigningKey) {
            missing.add(SIGNING_KEY_VARIABLE);
            missing.add(KEY_ID_VARIABLE);
        }
        if (!hasSecretKeys) {
            missing.add(SECRET_KEYS_VARIABLE);
        }
        if (!hasHmacKey) {
            missing.add(HMAC_KEY_VARIABLE);
        }
        if (!missing.isEmpty() && !ephemeralAllowed) {
            throw new IllegalStateException("Missing key material: " + String.join(", ", missing)
                    + ". Provide these environment variables; ephemeral keys are generated only in the local"
                    + " profile.");
        }

        SigningKey signingKey = hasSigningKey
                ? parseSigningKey(properties.jwtSigningKey(), properties.jwtKeyId())
                : generateSigningKey();
        SecretKeyRing secretKeys = hasSecretKeys ? parseSecretKeys(properties.secretKeys()) : generateSecretKeys();
        SecretKey hmacKey = hasHmacKey ? parseHmacKey(properties.hmacKey()) : generateHmacKey();

        return new Result(new KeyMaterial(signingKey, secretKeys, hmacKey), List.copyOf(missing));
    }

    private static SigningKey parseSigningKey(String value, String keyId) {
        String trimmedKeyId = keyId.strip();
        requireKeyId(trimmedKeyId, KEY_ID_VARIABLE);
        if (value.contains("BEGIN EC PRIVATE KEY") || value.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException(SIGNING_KEY_VARIABLE + " must be a PKCS#8 key (BEGIN PRIVATE KEY)");
        }
        String base64 = value.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        byte[] der = decodeBase64(base64, SIGNING_KEY_VARIABLE);

        PrivateKey privateKey;
        try {
            privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(SIGNING_KEY_VARIABLE + " is not a valid PKCS#8 EC private key");
        }
        ECPrivateKey ecPrivateKey = (ECPrivateKey) privateKey;
        ECNamedCurveParameterSpec curve = ECNamedCurveTable.getParameterSpec(CURVE);
        if (!ecPrivateKey.getParams().getOrder().equals(curve.getN())) {
            throw new IllegalStateException(SIGNING_KEY_VARIABLE + " must be a P-256 (secp256r1) key");
        }
        return new SigningKey(trimmedKeyId, ecPrivateKey, derivePublicKey(ecPrivateKey, curve));
    }

    /** Computes Q = d * G, because a PKCS#8 file does not always carry the public key. */
    private static ECPublicKey derivePublicKey(ECPrivateKey privateKey, ECNamedCurveParameterSpec curve) {
        org.bouncycastle.math.ec.ECPoint q =
                curve.getG().multiply(privateKey.getS()).normalize();
        ECPoint point = new ECPoint(
                q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger());
        try {
            return (ECPublicKey)
                    KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, privateKey.getParams()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot derive the public key of " + SIGNING_KEY_VARIABLE);
        }
    }

    private static SecretKeyRing parseSecretKeys(String value) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        String currentKeyId = null;
        for (String entry : value.split(",")) {
            String trimmed = entry.strip();
            int separator = trimmed.indexOf(':');
            if (separator <= 0) {
                throw new IllegalStateException(
                        SECRET_KEYS_VARIABLE + " must have the form id:base64key[,id:base64key]");
            }
            String keyId = trimmed.substring(0, separator);
            requireKeyId(keyId, SECRET_KEYS_VARIABLE);
            byte[] key =
                    decodeBase64(trimmed.substring(separator + 1), SECRET_KEYS_VARIABLE + " (key id " + keyId + ")");
            if (key.length != AES_KEY_BYTES) {
                throw new IllegalStateException(SECRET_KEYS_VARIABLE + " key id " + keyId + " must be " + AES_KEY_BYTES
                        + " bytes (AES-256), but is " + key.length);
            }
            if (keys.putIfAbsent(keyId, new SecretKeySpec(key, "AES")) != null) {
                throw new IllegalStateException(SECRET_KEYS_VARIABLE + " repeats key id " + keyId);
            }
            if (currentKeyId == null) {
                currentKeyId = keyId;
            }
        }
        return new SecretKeyRing(currentKeyId, keys);
    }

    private static SecretKey parseHmacKey(String value) {
        byte[] key = decodeBase64(value.strip(), HMAC_KEY_VARIABLE);
        if (key.length < MIN_HMAC_KEY_BYTES) {
            throw new IllegalStateException(
                    HMAC_KEY_VARIABLE + " must be at least " + MIN_HMAC_KEY_BYTES + " bytes, but is " + key.length);
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    private SigningKey generateSigningKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(CURVE), random);
            KeyPair pair = generator.generateKeyPair();
            return new SigningKey(
                    "ephemeral-" + randomHex(4), (ECPrivateKey) pair.getPrivate(), (ECPublicKey) pair.getPublic());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot generate an ephemeral ES256 key", exception);
        }
    }

    private SecretKeyRing generateSecretKeys() {
        String keyId = "ephemeral-" + randomHex(4);
        return new SecretKeyRing(keyId, Map.of(keyId, new SecretKeySpec(randomBytes(AES_KEY_BYTES), "AES")));
    }

    private SecretKey generateHmacKey() {
        return new SecretKeySpec(randomBytes(MIN_HMAC_KEY_BYTES), "HmacSHA256");
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private String randomHex(int bytes) {
        return HexFormat.of().formatHex(randomBytes(bytes));
    }

    private static void requireKeyId(String keyId, String variable) {
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalStateException(
                    variable + " has an invalid key id: use 1 to 64 letters, digits, '.', '_' or '-'");
        }
    }

    private static byte[] decodeBase64(String value, String variable) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(variable + " is not valid Base64");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
