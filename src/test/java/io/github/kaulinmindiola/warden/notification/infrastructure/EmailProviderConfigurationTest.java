package io.github.kaulinmindiola.warden.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.notification.domain.EmailProviders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** The primary provider is chosen by configuration (REQ-INT-008). */
class EmailProviderConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmailProviderConfiguration.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues(
                    "warden.notification.providers.a-url=http://localhost:8091",
                    "warden.notification.providers.b-url=http://localhost:8092");

    @Test
    void providerAIsThePrimaryByDefault() {
        runner.run(context -> {
            EmailProviders providers = context.getBean(EmailProviders.class);
            assertThat(providers.primary().name()).isEqualTo("A");
            assertThat(providers.secondary().name()).isEqualTo("B");
        });
    }

    @Test
    void providerBCanBeThePrimary() {
        runner.withPropertyValues("warden.notification.providers.primary=b").run(context -> {
            EmailProviders providers = context.getBean(EmailProviders.class);
            assertThat(providers.primary().name()).isEqualTo("B");
            assertThat(providers.secondary().name()).isEqualTo("A");
        });
    }

    @Test
    void theCircuitBreakersUseTheSrsValuesUnlessConfigured() {
        runner.run(context -> assertThat(
                        context.getBean(ProviderCircuitBreakers.class).config().getSlidingWindowSize())
                .isEqualTo(20));
        runner.withPropertyValues("warden.notification.circuit-breaker.sliding-window-size=40")
                .run(context -> assertThat(context.getBean(ProviderCircuitBreakers.class)
                                .config()
                                .getSlidingWindowSize())
                        .isEqualTo(40));
    }

    @Test
    void anUnknownPrimaryStopsTheStartup() {
        runner.withPropertyValues("warden.notification.providers.primary=c")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("warden.notification.providers.primary must be a or b"));
    }

    @Test
    void aMissingUrlStopsTheStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmailProviderConfiguration.class)
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withPropertyValues("warden.notification.providers.a-url=http://localhost:8091")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("warden.notification.providers.b-url is required"));
    }
}
