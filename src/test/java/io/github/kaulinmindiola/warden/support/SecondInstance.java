package io.github.kaulinmindiola.warden.support;

import io.github.kaulinmindiola.warden.WardenApplication;
import io.github.kaulinmindiola.warden.shared.crypto.KeyMaterial;
import io.github.kaulinmindiola.warden.shared.crypto.SecretKeyRing;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Another running instance of the application, connected to the same PostgreSQL, Redis and
 * simulated providers as the shared test context but sharing nothing in memory with it: its own
 * beans, connection pool, circuit breakers, keys and real clock. Used to show that work left by one
 * instance, or by an instance that is gone, is picked up by another (REQ-AVAIL-003, REQ-DIST-001).
 *
 * <p>It runs the {@code test} profile, so its workers do not tick by themselves; tests call them.
 * Close it, in a try-with-resources block, when done. Started with the keys of another context,
 * it accepts that context's tokens and opens its sealed secrets, as instances of one deployment
 * configured with the same keys do.
 */
public final class SecondInstance implements AutoCloseable {

    private final ConfigurableApplicationContext context;

    private SecondInstance(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** Starts an instance with its own ephemeral keys, its API and its actuator on random ports. */
    public static SecondInstance start() {
        return start(Map.of());
    }

    /**
     * Starts an instance with the given keys, as every instance of one deployment has: tokens issued
     * by one are accepted by the other and secrets sealed by one are opened by the other.
     */
    public static SecondInstance start(KeyMaterial keys) {
        SecretKeyRing ring = keys.secretKeys();
        String secretKeys = ring.keyIds().stream()
                .map(id -> id + ":" + base64(ring.find(id).orElseThrow().getEncoded()))
                .collect(Collectors.joining(","));
        return start(Map.of(
                "warden.keys.jwt-signing-key",
                        base64(keys.signingKey().privateKey().getEncoded()),
                "warden.keys.jwt-key-id", keys.signingKey().keyId(),
                "warden.keys.secret-keys", secretKeys,
                "warden.keys.hmac-key", base64(keys.hmacKey().getEncoded())));
    }

    private static SecondInstance start(Map<String, Object> extra) {
        Map<String, Object> properties = new HashMap<>(SharedContainers.properties());
        properties.putAll(extra);
        properties.put("server.port", "0");
        properties.put("management.server.port", "0");
        ConfigurableApplicationContext context = new SpringApplicationBuilder(WardenApplication.class)
                .profiles("test")
                .initializers(started -> started.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource("second-instance", properties)))
                .run();
        return new SecondInstance(context);
    }

    /** The base URL of this instance's API, for example {@code http://localhost:54321/api/v1}. */
    public String apiUrl() {
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port") + IamTestClient.API;
    }

    /** A bean of this instance. */
    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Override
    public void close() {
        context.close();
    }
}
