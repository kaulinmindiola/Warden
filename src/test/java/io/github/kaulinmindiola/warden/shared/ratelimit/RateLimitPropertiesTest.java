package io.github.kaulinmindiola.warden.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class RateLimitPropertiesTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Reads the main {@code application.yaml} alone, not the test classpath override, which raises
     * the per-IP capacities for the shared test context.
     */
    @Test
    void theDefaultsShippedInApplicationYamlAreTheSrsValues() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        RateLimitProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("warden.ratelimit", RateLimitProperties.class)
                .get();

        assertThat(properties.limitFor(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL))
                .isEqualTo(new RateLimitProperties.Limit(5, Duration.ofMinutes(15)));
        assertThat(properties.limitFor(RateLimitPolicy.LOGIN_PER_IP))
                .isEqualTo(new RateLimitProperties.Limit(20, Duration.ofMinutes(1)));
        assertThat(properties.limitFor(RateLimitPolicy.PASSWORD_RESET_PER_EMAIL))
                .isEqualTo(new RateLimitProperties.Limit(3, Duration.ofHours(1)));
        assertThat(properties.limitFor(RateLimitPolicy.PASSWORD_RESET_PER_IP))
                .isEqualTo(new RateLimitProperties.Limit(10, Duration.ofHours(1)));
        assertThat(properties.limitFor(RateLimitPolicy.REFRESH_PER_IP))
                .isEqualTo(new RateLimitProperties.Limit(60, Duration.ofMinutes(1)));
        assertThat(properties.limitFor(RateLimitPolicy.VERIFICATION_RESEND_PER_ACCOUNT))
                .isEqualTo(new RateLimitProperties.Limit(3, Duration.ofHours(1)));
        assertThat(properties.limitFor(RateLimitPolicy.INVITATIONS_PER_WORKSPACE))
                .isEqualTo(new RateLimitProperties.Limit(50, Duration.ofDays(1)));
        assertThat(properties.limitFor(RateLimitPolicy.INVITATIONS_PER_INVITER))
                .isEqualTo(new RateLimitProperties.Limit(100, Duration.ofDays(1)));
        assertThat(properties.redisCommandTimeout()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void everyPolicyBindsFromThePropertyNamedAfterItsKey() {
        Map<String, String> source = new HashMap<>();
        int capacity = 1;
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            source.put("warden.ratelimit." + policy.key() + ".capacity", Integer.toString(capacity++));
            source.put("warden.ratelimit." + policy.key() + ".window", "90s");
        }
        source.put("warden.ratelimit.redis-command-timeout", "1s");

        RateLimitProperties properties = bind(source);

        int expectedCapacity = 1;
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            assertThat(properties.limitFor(policy))
                    .as(policy.key())
                    .isEqualTo(new RateLimitProperties.Limit(expectedCapacity++, Duration.ofSeconds(90)));
        }
        assertThat(properties.redisCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void theRedisCommandTimeoutDefaultsTo200Milliseconds() {
        Map<String, String> source = new HashMap<>();
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            source.put("warden.ratelimit." + policy.key() + ".capacity", "5");
            source.put("warden.ratelimit." + policy.key() + ".window", "15m");
        }

        assertThat(bind(source).redisCommandTimeout()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void aMissingLimitStopsStartupNamingItsProperty() {
        RateLimitProperties.Limit limit = new RateLimitProperties.Limit(1, WINDOW);

        assertThatThrownBy(() -> new RateLimitProperties(
                        limit, limit, limit, limit, null, limit, limit, limit, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("warden.ratelimit.refresh-per-ip must be configured");
    }

    @Test
    void aNonPositiveCapacityWindowOrTimeoutIsRejected() {
        RateLimitProperties.Limit limit = new RateLimitProperties.Limit(1, WINDOW);

        assertThatThrownBy(() -> new RateLimitProperties.Limit(0, WINDOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new RateLimitProperties(limit, limit, limit, limit, limit, limit, limit, limit, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RateLimitProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("warden.ratelimit", RateLimitProperties.class)
                .get();
    }
}
