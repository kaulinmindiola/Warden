package io.github.kaulinmindiola.warden.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The key file of the reference environment (REQ-INST-002, REQ-DIST-001). */
class KeyFileGeneratorTest {

    private final SecureRandom random = new SecureRandom();

    @TempDir
    Path directory;

    @Test
    @Verifies("REQ-INST-002")
    void theGeneratedFileHoldsEveryKeyInAFormTheApplicationAccepts() {
        Map<String, String> variables = parse(KeyFileGenerator.generate(random));

        assertThat(variables)
                .containsOnlyKeys(
                        KeyMaterialLoader.SIGNING_KEY_VARIABLE,
                        KeyMaterialLoader.KEY_ID_VARIABLE,
                        KeyMaterialLoader.SECRET_KEYS_VARIABLE,
                        KeyMaterialLoader.HMAC_KEY_VARIABLE);
        assertThat(variables.get(KeyMaterialLoader.KEY_ID_VARIABLE)).startsWith(KeyFileGenerator.KEY_ID_PREFIX);

        KeyMaterialLoader.Result loaded = new KeyMaterialLoader(random)
                .load(
                        new KeyProperties(
                                variables.get(KeyMaterialLoader.SIGNING_KEY_VARIABLE),
                                variables.get(KeyMaterialLoader.KEY_ID_VARIABLE),
                                variables.get(KeyMaterialLoader.SECRET_KEYS_VARIABLE),
                                variables.get(KeyMaterialLoader.HMAC_KEY_VARIABLE),
                                false),
                        false);

        assertThat(loaded.generated()).as("nothing left to generate").isEmpty();
        assertThat(loaded.keyMaterial().signingKey().keyId())
                .isEqualTo(variables.get(KeyMaterialLoader.KEY_ID_VARIABLE));
    }

    @Test
    void everyFileHasKeysOfItsOwn() {
        assertThat(KeyFileGenerator.generate(random)).isNotEqualTo(KeyFileGenerator.generate(random));
    }

    @Test
    @Verifies("REQ-DIST-001")
    void anExistingFileIsKeptSoEveryInstanceAndRestartUsesTheSameKeys() throws Exception {
        Path file = directory.resolve("keys").resolve("keys.env");

        assertThat(KeyFileGenerator.write(file, random)).isTrue();
        String first = Files.readString(file);
        assertThat(KeyFileGenerator.write(file, random)).isFalse();

        assertThat(Files.readString(file)).isEqualTo(first);
        try (var entries = Files.list(file.getParent())) {
            assertThat(entries).containsExactly(file);
        }
    }

    @Test
    void onPosixFileSystemsOnlyItsOwnerCanReadTheFile() throws Exception {
        assumeThat(Files.getFileStore(directory).supportsFileAttributeView("posix"))
                .isTrue();
        Path file = directory.resolve("keys.env");

        KeyFileGenerator.write(file, random);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }

    private static Map<String, String> parse(String content) {
        Map<String, String> variables = new LinkedHashMap<>();
        content.lines().forEach(line -> {
            int separator = line.indexOf('=');
            variables.put(line.substring(0, separator), line.substring(separator + 1));
        });
        return variables;
    }
}
