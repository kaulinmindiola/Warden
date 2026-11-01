package io.github.kaulinmindiola.warden.notification.application;

import io.github.kaulinmindiola.warden.notification.domain.EmailMessage;
import io.github.kaulinmindiola.warden.notification.domain.EmailProvider;
import io.github.kaulinmindiola.warden.notification.domain.EmailProviders;
import io.github.kaulinmindiola.warden.notification.domain.ProviderResult;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderCircuitBreakers;
import java.util.List;
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
 * same address (REQ-FUNC-056). Each call's result is recorded in its provider's breaker.
 */
@Component
public class ProviderFailover {

    private final EmailProviders providers;
    private final ProviderCircuitBreakers breakers;

    public ProviderFailover(EmailProviders providers, ProviderCircuitBreakers breakers) {
        this.providers = providers;
        this.breakers = breakers;
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
            breakers.record(provider.name(), result == ProviderResult.TRANSIENT_FAILURE, System.nanoTime() - start);
            lastCalled = provider.name();
            if (result != ProviderResult.TRANSIENT_FAILURE) {
                return new SendOutcome(result, provider.name());
            }
        }
        return new SendOutcome(ProviderResult.TRANSIENT_FAILURE, lastCalled);
    }
}
