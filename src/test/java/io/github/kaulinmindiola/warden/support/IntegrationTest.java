package io.github.kaulinmindiola.warden.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests ({@code *IT}).
 *
 * <p>Every subclass shares the same containers and, because the configuration is identical, the
 * same cached Spring context. The application connects as {@code warden_app}; Flyway migrates as
 * {@code warden_owner}. The {@link MutableClock} is reset before each test because the context is
 * shared between tests.
 *
 * <p>The static helpers give raw JDBC access for database-level tests (Row Level Security, role
 * privileges) that must run without the application's filters.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    protected static void sharedInfrastructure(DynamicPropertyRegistry registry) {
        SharedContainers.registerProperties(registry);
    }

    @BeforeEach
    protected void resetClock() {
        clock.reset();
    }

    /** Non-pooled connections as {@code warden_owner}, the role that owns every schema object. */
    protected static DataSource ownerDataSource() {
        return new DriverManagerDataSource(
                SharedContainers.POSTGRES.getJdbcUrl(), "warden_owner", SharedContainers.OWNER_PASSWORD);
    }

    /**
     * Non-pooled connections as the container superuser, which bypasses Row Level Security. Only
     * for seeding data and for negative controls; never for asserting isolation.
     */
    protected static DataSource superuserDataSource() {
        return new DriverManagerDataSource(
                SharedContainers.POSTGRES.getJdbcUrl(),
                SharedContainers.POSTGRES.getUsername(),
                SharedContainers.POSTGRES.getPassword());
    }

    /**
     * A dedicated Hikari pool as {@code warden_app} with a fixed size. The caller closes it. Used
     * when the pool size is part of what a test proves, such as connection reuse.
     */
    protected static HikariDataSource newApplicationRolePool(String poolName, int size) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(SharedContainers.POSTGRES.getJdbcUrl());
        config.setUsername("warden_app");
        config.setPassword(SharedContainers.APP_PASSWORD);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        return new HikariDataSource(config);
    }
}
