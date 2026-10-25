package io.github.kaulinmindiola.warden.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.Verifies;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Startup behaviour of the key material per profile, with a minimal application context. */
class KeyConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(KeyConfiguration.class);

    @Test
    @Verifies("REQ-INST-002")
    void theProdLikeProfileRefusesToStartWithoutKeys() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod-like"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageStartingWith("Missing key material: WARDEN_JWT_SIGNING_KEY, WARDEN_JWT_KEY_ID,"
                                    + " WARDEN_SECRET_KEYS, WARDEN_HMAC_KEY.");
                });
    }

    @Test
    @Verifies("REQ-INST-002")
    void theProdLikeProfileRefusesEphemeralKeysEvenWhenAllowed() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod-like"))
                .withPropertyValues("warden.keys.allow-ephemeral=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageStartingWith("Missing key material"));
    }

    @Test
    @Verifies("REQ-INST-002")
    void withoutAnyProfileKeysAreRequired() {
        runner.run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageStartingWith("Missing key material"));
    }

    @Test
    void whenEphemeralKeysAreAllowedTheyAreGenerated() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("warden.keys.allow-ephemeral=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(KeyMaterial.class);
                    assertThat(context.getBean(KeyMaterial.class).signingKey().keyId())
                            .startsWith("ephemeral-");
                });
    }
}
