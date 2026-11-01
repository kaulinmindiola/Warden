package io.github.kaulinmindiola.warden.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.notification.domain.LinkType;
import io.github.kaulinmindiola.warden.support.Verifies;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The base URLs come from configuration, so they change without recompiling (REQ-INT-009). */
class LinkConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(LinkConfiguration.class);

    @Test
    @Verifies("REQ-INT-009")
    void theLocalDefaultsAreTheConsumerPagesOnPort3001() {
        runner.run(context -> {
            LinkBuilder builder = context.getBean(LinkBuilder.class);
            assertThat(builder.build(LinkType.EMAIL_VERIFICATION, "t"))
                    .isEqualTo("http://localhost:3001/verify-email#token=t");
            assertThat(builder.build(LinkType.PASSWORD_RESET, "t"))
                    .isEqualTo("http://localhost:3001/reset-password#token=t");
            assertThat(builder.build(LinkType.INVITATION, "t"))
                    .isEqualTo("http://localhost:3001/accept-invitation#token=t");
        });
    }

    @Test
    @Verifies("REQ-INT-009")
    void changingABaseUrlInConfigurationChangesItsLinks() {
        runner.withPropertyValues("warden.notification.links.reset-password=https://accounts.example.test/reset")
                .run(context -> assertThat(context.getBean(LinkBuilder.class).build(LinkType.PASSWORD_RESET, "t"))
                        .isEqualTo("https://accounts.example.test/reset#token=t"));
    }

    @Test
    void aBaseUrlWithAQueryStopsTheStartup() {
        runner.withPropertyValues("warden.notification.links.accept-invitation=http://localhost:3001/accept?x=1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("warden.notification.links.accept-invitation must have no query"));
    }
}
