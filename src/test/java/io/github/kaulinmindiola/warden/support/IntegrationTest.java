package io.github.kaulinmindiola.warden.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
}
