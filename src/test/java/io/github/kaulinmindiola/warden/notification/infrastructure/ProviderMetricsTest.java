package io.github.kaulinmindiola.warden.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.notification.application.ProviderFailover;
import io.github.kaulinmindiola.warden.notification.application.SendOutcome;
import io.github.kaulinmindiola.warden.notification.domain.EmailMessage;
import io.github.kaulinmindiola.warden.notification.domain.EmailProvider;
import io.github.kaulinmindiola.warden.notification.domain.EmailProviders;
import io.github.kaulinmindiola.warden.notification.domain.ProviderResult;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Calls, latency and breaker state of each e-mail provider (REQ-OBS-002, SDD V19). */
class ProviderMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FakeProvider a = new FakeProvider("A");
    private final FakeProvider b = new FakeProvider("B");
    private final EmailProviders providers = new EmailProviders(a, b);
    private final ProviderCircuitBreakers breakers = new ProviderCircuitBreakers(CircuitBreakerProperties.defaults());
    private final ProviderMetrics metrics = new ProviderMetrics(registry, providers, breakers);

    @Test
    @Verifies("REQ-OBS-002")
    void everySeriesOfBothProvidersIsExposedFromZeroWithClosedBreakers() {
        for (String provider : new String[] {"A", "B"}) {
            for (ProviderResult result : ProviderResult.values()) {
                assertThat(attempts(provider, result)).isZero();
                assertThat(requests(provider, result)).isZero();
            }
            assertThat(circuit(provider, "closed")).isEqualTo(1);
            assertThat(circuit(provider, "open")).isZero();
            assertThat(circuit(provider, "half_open")).isZero();
        }
    }

    @Test
    @Verifies("REQ-OBS-002")
    void aCallIsCountedAndTimedUnderItsProviderAndResultOnly() {
        metrics.recordCall("A", ProviderResult.TRANSIENT_FAILURE, TimeUnit.MILLISECONDS.toNanos(40));

        assertThat(attempts("A", ProviderResult.TRANSIENT_FAILURE)).isEqualTo(1);
        assertThat(requests("A", ProviderResult.TRANSIENT_FAILURE)).isEqualTo(1);
        assertThat(registry.get(ProviderMetrics.REQUESTS)
                        .tags(ProviderMetrics.PROVIDER_TAG, "A", ProviderMetrics.RESULT_TAG, "transient_failure")
                        .timer()
                        .totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(40);
        assertThat(attempts("A", ProviderResult.ACCEPTED) + attempts("B", ProviderResult.TRANSIENT_FAILURE))
                .isZero();
    }

    @Test
    void aCallToAnUnknownProviderAddsNoSeries() {
        metrics.recordCall("C", ProviderResult.ACCEPTED, 1);

        assertThat(registry.find(ProviderMetrics.ATTEMPTS)
                        .tag(ProviderMetrics.PROVIDER_TAG, "C")
                        .counter())
                .isNull();
    }

    @Test
    @Verifies("REQ-OBS-002")
    void anOpenBreakerIsShownForItsProviderOnly() {
        for (int call = 0; call < 10; call++) {
            breakers.record("A", true, 1);
        }

        assertThat(circuit("A", "open")).isEqualTo(1);
        assertThat(circuit("A", "closed")).isZero();
        assertThat(circuit("B", "closed")).isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-OBS-002", "REQ-FUNC-055"})
    void aFailoverRecordsTheFailureOfThePrimaryAndTheAcceptanceOfTheSecondary() {
        a.answer = ProviderResult.TRANSIENT_FAILURE;
        ProviderFailover failover = new ProviderFailover(providers, breakers, metrics);

        assertThat(failover.send(message())).isEqualTo(new SendOutcome(ProviderResult.ACCEPTED, "B"));

        assertThat(attempts("A", ProviderResult.TRANSIENT_FAILURE)).isEqualTo(1);
        assertThat(attempts("B", ProviderResult.ACCEPTED)).isEqualTo(1);
        assertThat(requests("A", ProviderResult.TRANSIENT_FAILURE) + requests("B", ProviderResult.ACCEPTED))
                .isEqualTo(2);
    }

    private double attempts(String provider, ProviderResult result) {
        return registry.get(ProviderMetrics.ATTEMPTS)
                .tags(
                        ProviderMetrics.PROVIDER_TAG,
                        provider,
                        ProviderMetrics.RESULT_TAG,
                        ProviderMetrics.resultTag(result))
                .counter()
                .count();
    }

    private long requests(String provider, ProviderResult result) {
        return registry.get(ProviderMetrics.REQUESTS)
                .tags(
                        ProviderMetrics.PROVIDER_TAG,
                        provider,
                        ProviderMetrics.RESULT_TAG,
                        ProviderMetrics.resultTag(result))
                .timer()
                .count();
    }

    private double circuit(String provider, String state) {
        return registry.get(ProviderMetrics.CIRCUIT_STATE)
                .tags(ProviderMetrics.PROVIDER_TAG, provider, ProviderMetrics.STATE_TAG, state)
                .gauge()
                .value();
    }

    private static EmailMessage message() {
        return new EmailMessage(UUID.randomUUID().toString(), "ana@mail.test", "Subject", "Body");
    }

    /** A provider that answers what it is told. */
    private static final class FakeProvider implements EmailProvider {

        private final String name;
        private volatile ProviderResult answer = ProviderResult.ACCEPTED;

        FakeProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ProviderResult send(EmailMessage message) {
            return answer;
        }
    }
}
