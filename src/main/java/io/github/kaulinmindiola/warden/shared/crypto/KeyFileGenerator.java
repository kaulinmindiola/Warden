package io.github.kaulinmindiola.warden.shared.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates the key material of a deployment once, as an environment file with the {@code WARDEN_*}
 * key variables (REQ-INST-002). In the reference environment the image's entry point runs it when
 * the file is missing from the key volume, so every instance starts with the same keys: tokens and
 * sealed secrets work across instances (REQ-DIST-001) and survive restarts, and no key is ever kept
 * in the repository.
 *
 * <pre>
 * java -cp "/app/BOOT-INF/classes:/app/BOOT-INF/lib/*" \
 *     io.github.kaulinmindiola.warden.shared.crypto.KeyFileGenerator /var/lib/warden/keys/keys.env
 * </pre>
 *
 * An existing file is left untouched; removing the volume generates new keys on the next start. The
 * file is readable by its owner only, and nothing is printed but its path.
 */
public final class KeyFileGenerator {

    /** Prefix of the key ids it generates, which tells generated keys from provided ones. */
    static final String KEY_ID_PREFIX = "generated-";

    private static final String CURVE = "secp256r1";

    private KeyFileGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: KeyFileGenerator <key file>");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        boolean created = write(file, new SecureRandom());
        System.out.println((created ? "Generated key material in " : "Key material already present in ") + file);
    }

    /**
     * Writes new key material to {@code file} unless it exists.
     *
     * @return whether the file was created
     */
    static boolean write(Path file, SecureRandom random) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".keys-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            Files.writeString(temporary, generate(random), StandardCharsets.US_ASCII);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }

    /** New key material in the format {@link KeyMaterialLoader} reads, one variable per line. */
    static String generate(SecureRandom random) {
        String keyId = KEY_ID_PREFIX + HexFormat.of().formatHex(bytes(random, 4));
        return KeyMaterialLoader.SIGNING_KEY_VARIABLE + "=" + base64(signingKey(random)) + "\n"
                + KeyMaterialLoader.KEY_ID_VARIABLE + "=" + keyId + "\n"
                + KeyMaterialLoader.SECRET_KEYS_VARIABLE + "=" + keyId + ":"
                + base64(bytes(random, KeyMaterialLoader.AES_KEY_BYTES)) + "\n"
                + KeyMaterialLoader.HMAC_KEY_VARIABLE + "="
                + base64(bytes(random, KeyMaterialLoader.MIN_HMAC_KEY_BYTES))
                + "\n";
    }

    /** A P-256 private key as PKCS#8 DER. */
    private static byte[] signingKey(SecureRandom random) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(CURVE), random);
            return generator.generateKeyPair().getPrivate().getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot generate an ES256 key", exception);
        }
    }

    private static byte[] bytes(SecureRandom random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
