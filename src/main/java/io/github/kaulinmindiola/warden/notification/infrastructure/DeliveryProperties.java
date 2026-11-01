package io.github.kaulinmindiola.warden.notification.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The delivery worker (SDD V14-A5, V16).
 *
 * @param concurrency how many deliveries of a batch are sent at the same time
 * @param batchSize how many due deliveries one tick claims
 * @param lease how long a claimed delivery is reserved for its worker; once it lapses, any
 *     instance may claim the delivery again
 * @param backoff the waits between attempts (REQ-REL-006)
 */
@ConfigurationProperties("warden.notification.delivery")
public record DeliveryProperties(
        @DefaultValue("8") int concurrency,
        @DefaultValue("16") int batchSize,
        @DefaultValue("30s") Duration lease,
        @DefaultValue Backoff backoff) {

    public DeliveryProperties {
        if (concurrency < 1 || batchSize < 1) {
            throw new IllegalArgumentException(
                    "warden.notification.delivery.concurrency and batch-size must be at least 1");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("warden.notification.delivery.lease must be positive");
        }
    }

    /**
     * {@code min(max, initial · multiplier^(n−1))}, with {@code ±jitter} at random (REQ-REL-006).
     *
     * @param jitter the fraction added or removed at random, in [0, 1); 0 disables it
     */
    public record Backoff(
            @DefaultValue("1s") Duration initial,
            @DefaultValue("2") double multiplier,
            @DefaultValue("60s") Duration max,
            @DefaultValue("0.5") double jitter) {}
}
