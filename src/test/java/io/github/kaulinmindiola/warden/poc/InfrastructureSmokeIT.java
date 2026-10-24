package io.github.kaulinmindiola.warden.poc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.annotations.UuidGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * POC-001: Testcontainers, Flyway, JPA, Redis and distributed Bucket4j against PostgreSQL 17 and
 * Redis 8.
 *
 * <p>Flyway migrates as {@code poc_owner} and the application connects as {@code poc_app}, which
 * previews the owner/application role split of step 0.10.
 */
@SpringBootTest(
        properties = {
            "spring.flyway.locations=classpath:db/poc",
            "spring.flyway.schemas=poc",
            "spring.flyway.default-schema=poc",
            "spring.jpa.hibernate.ddl-auto=none"
        })
class InfrastructureSmokeIT {

    private static final int REDIS_PORT = 6379;

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17")).withInitScript("poc/postgres-roles.sql");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(REDIS_PORT);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "poc_app");
        registry.add("spring.datasource.password", () -> "app");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "poc_owner");
        registry.add("spring.flyway.password", () -> "owner");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void containersRunAndTheContextIsConnectedToPostgres17() {
        String serverVersion =
                jdbc().sql("SHOW server_version").query(String.class).single();

        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(REDIS.isRunning()).isTrue();
        assertThat(serverVersion).startsWith("17.");
    }

    @Test
    void flywayMigratesAsTheOwnerWhileTheApplicationConnectsAsTheAppRole() {
        String currentUser =
                jdbc().sql("SELECT current_user").query(String.class).single();
        String tableOwner = jdbc().sql(
                        "SELECT tableowner FROM pg_tables WHERE schemaname = 'poc' AND tablename = 'poc_item'")
                .query(String.class)
                .single();
        Integer appliedMigrations = jdbc().sql(
                        "SELECT count(*) FROM poc.flyway_schema_history WHERE version = '1' AND success")
                .query(Integer.class)
                .single();

        assertThat(currentUser).isEqualTo("poc_app");
        assertThat(tableOwner).isEqualTo("poc_owner");
        assertThat(appliedMigrations).isEqualTo(1);
    }

    @Test
    void jpaPersistsAnEntityThatIsReadBackWithJdbc() {
        UUID id = UUID.randomUUID();

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> entityManager.persist(new PocItem(id, "first")));

        String storedName = jdbc().sql("SELECT name FROM poc.poc_item WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(storedName).isEqualTo("first");
    }

    @Test
    void reportsWhetherHibernateProvidesAUuidV7Generator() {
        List<String> styles =
                Arrays.stream(UuidGenerator.Style.values()).map(Enum::name).toList();

        System.out.println("POC-001 Hibernate UuidGenerator styles: " + styles + " (VERSION_7 available: "
                + styles.contains("VERSION_7") + ")");
        assertThat(styles).isNotEmpty();
    }

    @Test
    void redisStoresAndReturnsAValue() {
        String key = "poc:key:" + UUID.randomUUID();

        redisTemplate.opsForValue().set(key, "value");

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("value");
    }

    @Test
    void bucket4jConsumesTokensInRedisWithAControllableClock() {
        MutableTimeMeter clock = new MutableTimeMeter();
        RedisClient client = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT)));
        try (StatefulRedisConnection<String, byte[]> connection =
                client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))) {
            var proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                    .clientClock(clock)
                    .build();
            BucketConfiguration configuration = BucketConfiguration.builder()
                    .addLimit(limit -> limit.capacity(2).refillIntervally(2, Duration.ofMinutes(1)))
                    .build();
            Bucket bucket = proxyManager.builder().build("poc:rate:" + UUID.randomUUID(), () -> configuration);

            assertThat(bucket.tryConsume(1)).isTrue();
            assertThat(bucket.tryConsume(1)).isTrue();
            assertThat(bucket.tryConsume(1)).isFalse();

            clock.advance(Duration.ofSeconds(61));

            assertThat(bucket.tryConsume(1)).isTrue();
        } finally {
            client.shutdown();
        }
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
