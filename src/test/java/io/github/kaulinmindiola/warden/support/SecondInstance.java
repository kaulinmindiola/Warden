package io.github.kaulinmindiola.warden.support;

import io.github.kaulinmindiola.warden.WardenApplication;
import java.util.HashMap;
import java.util.Map;
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
 * Close it, in a try-with-resources block, when done.
 */
public final class SecondInstance implements AutoCloseable {

    private final ConfigurableApplicationContext context;

    private SecondInstance(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** Starts an instance with its API and its actuator on random ports. */
    public static SecondInstance start() {
        Map<String, Object> properties = new HashMap<>(SharedContainers.properties());
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

    /** A bean of this instance. */
    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    @Override
    public void close() {
        context.close();
    }
}
