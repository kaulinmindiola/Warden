package io.github.kaulinmindiola.warden.notification.application;

import io.github.kaulinmindiola.warden.notification.domain.EmailMessage;
import io.github.kaulinmindiola.warden.notification.domain.EmailProvider;
import io.github.kaulinmindiola.warden.notification.domain.EmailProviders;
import io.github.kaulinmindiola.warden.notification.domain.ProviderResult;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderCircuitBreakers;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One delivery attempt with in-attempt failover (REQ-FUNC-055, REQ-REL-004, SDD V14-A5):
 *
 * <ol>
 *   <li>the primary, unless its breaker is open;
 *   <li>the secondary, if the primary failed transiently or could not be called, and unless its
 *       breaker is open.
 * </ol>
 *
 * <p>A permanent rejection ends the attempt with no failover: another provider would reject the
 * same address (REQ-FUNC-056). Each call's result is recorded in its provider's breaker and in the
 * provider metrics (REQ-OBS-002).
 */
@Component
public class ProviderFailover {

    private final EmailProviders providers;
    private final ProviderCircuitBreakers breakers;
    private final ProviderMetrics metrics;

    /** A failover whose calls are measured in a registry of its own; for tests. */
    public ProviderFailover(EmailProviders providers, ProviderCircuitBreakers breakers) {
        this(providers, breakers, new ProviderMetrics(new SimpleMeterRegistry(), providers, breakers));
    }

    @Autowired
    public ProviderFailover(EmailProviders providers, ProviderCircuitBreakers breakers, ProviderMetrics metrics) {
        this.providers = providers;
        this.breakers = breakers;
        this.metrics = metrics;
    }

    /** Sends the message through the first provider that takes or permanently rejects it. */
    public SendOutcome send(EmailMessage message) {
        String lastCalled = SendOutcome.NONE;
        for (EmailProvider provider : List.of(providers.primary(), providers.secondary())) {
            if (!breakers.tryAcquire(provider.name())) {
                continue;
            }
            long start = System.nanoTime();
            ProviderResult result = provider.send(message);
            long elapsed = System.nanoTime() - start;
            breakers.record(provider.name(), result == ProviderResult.TRANSIENT_FAILURE, elapsed);
            metrics.recordCall(provider.name(), result, elapsed);
            lastCalled = provider.name();
            if (result != ProviderResult.TRANSIENT_FAILURE) {
                return new SendOutcome(result, provider.name());
            }
        }
        return new SendOutcome(ProviderResult.TRANSIENT_FAILURE, lastCalled);
    }
}
