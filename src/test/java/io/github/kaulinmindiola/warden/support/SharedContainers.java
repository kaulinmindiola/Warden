package io.github.kaulinmindiola.warden.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 17 and Redis 8 containers shared by every integration test in the JVM.
 *
 * <p>PostgreSQL runs {@code ops/postgres/init/01-roles.sh}, the same script as Docker Compose, so
 * tests use the real owner/application role split. The application connects as
 * {@code warden_app} and Flyway migrates as {@code warden_owner}; nothing connects as the
 * container superuser, which bypasses Row Level Security and would make isolation tests pass
 * falsely. Role passwords are random for every run.
 *
 * <p>Two in-process WireMock servers stand in for the e-mail providers A and B, with the mappings
 * of {@code ops/providers} that Docker Compose uses too: the recipient's domain decides the answer.
 */
final class SharedContainers {

    private static final String ROLES_SCRIPT = "ops/postgres/init/01-roles.sh";
    private static final String DATABASE = "warden";
    private static final int REDIS_PORT = 6379;

    static final String OWNER_PASSWORD = UUID.randomUUID().toString();
    static final String APP_PASSWORD = UUID.randomUUID().toString();

    static final PostgreSQLContainer POSTGRES = createPostgres();
    static final GenericContainer<?> REDIS = createRedis();
    static final WireMockServer PROVIDER_A = createProvider("ops/providers/a");
    static final WireMockServer PROVIDER_B = createProvider("ops/providers/b");

    static {
        POSTGRES.start();
        REDIS.start();
        PROVIDER_A.start();
        PROVIDER_B.start();
    }

    private SharedContainers() {}

    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "warden_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "warden_owner");
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("warden.notification.providers.a-url", PROVIDER_A::baseUrl);
        registry.add("warden.notification.providers.b-url", PROVIDER_B::baseUrl);
    }

    private static PostgreSQLContainer createPostgres() {
        if (!Files.isRegularFile(Path.of(ROLES_SCRIPT))) {
            throw new IllegalStateException("Database role script not found at "
                    + Path.of(ROLES_SCRIPT).toAbsolutePath() + "; integration tests must run from the repository root");
        }
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        container.withDatabaseName(DATABASE);
        container.withEnv("WARDEN_DB_OWNER_PASSWORD", OWNER_PASSWORD);
        container.withEnv("WARDEN_DB_APP_PASSWORD", APP_PASSWORD);
        container.withCopyFileToContainer(
                MountableFile.forHostPath(ROLES_SCRIPT, 0755), "/docker-entrypoint-initdb.d/01-roles.sh");
        // Every cached test context holds a pool, and several tests open dedicated ones: room for
        // all of them beyond PostgreSQL's default of 100 connections. fsync=off is the container's
        // own default, kept because this command replaces it.
        container.withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300");
        return container;
    }

    private static WireMockServer createProvider(String directory) {
        if (!Files.isDirectory(Path.of(directory, "mappings"))) {
            throw new IllegalStateException("Provider mappings not found at "
                    + Path.of(directory).toAbsolutePath() + "; integration tests must run from the repository root");
        }
        return new WireMockServer(WireMockConfiguration.options().dynamicPort().usingFilesUnderDirectory(directory));
    }

    private static GenericContainer<?> createRedis() {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:8"));
        container.withExposedPorts(REDIS_PORT);
        container.withCommand("redis-server", "--save", "", "--appendonly", "no");
        return container;
    }
}
