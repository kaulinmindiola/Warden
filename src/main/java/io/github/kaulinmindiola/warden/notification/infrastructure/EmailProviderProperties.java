package io.github.kaulinmindiola.warden.notification.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The two simulated e-mail providers (REQ-INT-008, SDD V09, V21).
 *
 * @param primary which provider is tried first: {@code a} or {@code b}
 * @param aUrl base URL of provider A
 * @param bUrl base URL of provider B
 * @param connectTimeout how long a connection may take to open
 * @param requestTimeout how long an answer may take, after which the call is a transient failure
 *     (REQ-REL-005)
 */
@ConfigurationProperties("warden.notification.providers")
public record EmailProviderProperties(
        @DefaultValue("a") String primary,
        URI aUrl,
        URI bUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration requestTimeout) {

    public EmailProviderProperties {
        if (!"a".equals(primary) && !"b".equals(primary)) {
            throw new IllegalArgumentException("warden.notification.providers.primary must be a or b");
        }
        Objects.requireNonNull(aUrl, "warden.notification.providers.a-url is required");
        Objects.requireNonNull(bUrl, "warden.notification.providers.b-url is required");
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("warden.notification.providers." + name + " must be positive");
        }
    }
}
