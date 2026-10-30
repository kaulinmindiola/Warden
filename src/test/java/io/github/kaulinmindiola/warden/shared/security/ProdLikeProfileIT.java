package io.github.kaulinmindiola.warden.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.WardenApplication;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The {@code prod-like} profile (REQ-SEC-019, REQ-INST-002): the API is served over HTTPS only,
 * every response carries HSTS, and the application refuses to start without its TLS keystore or
 * its keys.
 *
 * <p>The context of this class runs the profile with a self-signed certificate created by the
 * JDK's keytool and freshly generated keys. The startup failures are checked by starting the
 * application again with one of them missing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("prod-like")
class ProdLikeProfileIT extends IntegrationTest {

    private static final String KEYSTORE_PASSWORD = UUID.randomUUID().toString();
    private static final Path KEYSTORE = createKeystore(KEYSTORE_PASSWORD);
    private static final Map<String, String> TLS = Map.of(
            "WARDEN_TLS_KEYSTORE", KEYSTORE.toUri().toString(), "WARDEN_TLS_KEYSTORE_PASSWORD", KEYSTORE_PASSWORD);
    private static final Map<String, String> KEYS = generateKeys();
    private static final String SESSIONS = "/api/v1/auth/sessions";

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void prodLikeSettings(DynamicPropertyRegistry registry) {
        TLS.forEach((name, value) -> registry.add(name, () -> value));
        KEYS.forEach((name, value) -> registry.add(name, () -> value));
    }

    @Test
    @Verifies("REQ-SEC-019")
    void theApiIsServedOverHttpsWithHsts() throws Exception {
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder()
                .sslContext(trustingTheTestCertificate())
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            response = client.send(
                    HttpRequest.newBuilder(URI.create("https://localhost:" + serverPort() + SESSIONS))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.sslSession())
                .hasValueSatisfying(session -> assertThat(session.getProtocol()).isIn("TLSv1.3", "TLSv1.2"));
        assertThat(response.headers().firstValue("Strict-Transport-Security"))
                .hasValueSatisfying(
                        hsts -> assertThat(hsts).contains("max-age=31536000").contains("includeSubDomains"));
    }

    @Test
    @Verifies("REQ-SEC-019")
    void plainHttpIsRejected() throws Exception {
        Integer status = null;
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            status = client.send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort() + SESSIONS))
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .statusCode();
        } catch (IOException rejected) {
            // The TLS connector closed the connection: rejected as well.
        }

        assertThat(status == null || status == 400)
                .as("plain HTTP is rejected (status %s)", status)
                .isTrue();
    }

    @Test
    @Verifies("REQ-SEC-019")
    void withoutTheTlsKeystoreTheApplicationDoesNotStart() {
        assertThatThrownBy(() -> startProdLike(KEYS).close()).hasStackTraceContaining("WARDEN_TLS_KEYSTORE");
    }

    @Test
    @Verifies("REQ-INST-002")
    void withoutKeysTheApplicationDoesNotStart() {
        Map<String, String> noKeys = new LinkedHashMap<>(TLS);
        KEYS.keySet().forEach(name -> noKeys.put(name, ""));

        assertThatThrownBy(() -> startProdLike(noKeys).close()).hasStackTraceContaining("Missing key material");
    }

    /**
     * Starts the application with only the prod-like profile, the shared containers and the given
     * settings, all as command-line arguments so that nothing from the environment overrides them.
     */
    private AutoCloseable startProdLike(Map<String, String> settings) {
        List<String> arguments = new ArrayList<>();
        for (String name : List.of(
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.flyway.url",
                "spring.flyway.user",
                "spring.flyway.password",
                "spring.data.redis.host",
                "spring.data.redis.port")) {
            arguments.add("--" + name + "=" + environment.getRequiredProperty(name));
        }
        arguments.add("--server.port=0");
        arguments.add("--management.server.port=0");
        settings.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        return new SpringApplicationBuilder(WardenApplication.class)
                .profiles("prod-like")
                .run(arguments.toArray(String[]::new));
    }

    private int serverPort() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }

    /** A client context that trusts only the self-signed certificate of this test. */
    private static SSLContext trustingTheTestCertificate() throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(KEYSTORE)) {
            keystore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("warden", keystore.getCertificate("warden"));
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    /** A PKCS#12 keystore with a self-signed certificate for localhost, created with keytool. */
    private static Path createKeystore(String password) {
        try {
            Path directory = Files.createTempDirectory("warden-tls");
            Path keystore = directory.resolve("warden-tls.p12");
            String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
            Process process = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", executable)
                                    .toString(),
                            "-genkeypair",
                            "-alias",
                            "warden",
                            "-keyalg",
                            "RSA",
                            "-keysize",
                            "2048",
                            "-validity",
                            "2",
                            "-dname",
                            "CN=localhost",
                            "-ext",
                            "san=dns:localhost,ip:127.0.0.1",
                            "-storetype",
                            "PKCS12",
                            "-keystore",
                            keystore.toString(),
                            "-storepass",
                            password,
                            "-keypass",
                            password)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool failed: " + output);
            }
            keystore.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            return keystore;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create the test keystore", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the test keystore", interrupted);
        }
    }

    /** Fresh, valid key material in the formats of REQ-INST-002. */
    private static Map<String, String> generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            SecureRandom random = new SecureRandom();
            byte[] secretKey = new byte[32];
            byte[] hmacKey = new byte[32];
            random.nextBytes(secretKey);
            random.nextBytes(hmacKey);
            Base64.Encoder base64 = Base64.getEncoder();
            Map<String, String> keys = new LinkedHashMap<>();
            keys.put(
                    "warden.keys.jwt-signing-key",
                    base64.encodeToString(
                            generator.generateKeyPair().getPrivate().getEncoded()));
            keys.put("warden.keys.jwt-key-id", "prod-like-test");
            keys.put("warden.keys.secret-keys", "k1:" + base64.encodeToString(secretKey));
            keys.put("warden.keys.hmac-key", base64.encodeToString(hmacKey));
            return keys;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not generate test keys", failure);
        }
    }
}
