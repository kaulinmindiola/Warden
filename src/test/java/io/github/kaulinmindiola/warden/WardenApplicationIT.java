package io.github.kaulinmindiola.warden;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that the full application context starts.
 *
 * <p>Temporary until step 0.11: the context starts without PostgreSQL or Redis. The connection pool
 * and Lettuce connect lazily, Hibernate starts without reading JDBC metadata, and Flyway is disabled.
 * Step 0.11 replaces these properties with the shared containers.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:postgresql://localhost:5432/warden",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
            "spring.flyway.enabled=false"
        })
class WardenApplicationIT {

    @Test
    void contextLoads() {}
}
