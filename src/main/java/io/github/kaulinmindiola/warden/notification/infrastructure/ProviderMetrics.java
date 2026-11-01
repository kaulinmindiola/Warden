package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.kaulinmindiola.warden.notification.domain.EmailProviders;
import io.github.kaulinmindiola.warden.notification.domain.ProviderResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Provider metrics of SDD V19 (REQ-OBS-002), exposed in Prometheus format on the management port:
 *
 * <ul>
 *   <li>{@code warden_notification_attempts_total{provider, result}}: every call made to a
 *       provider, by how it answered; the error rate of a provider is the share of {@code
 *       transient_failure};
 *   <li>{@code warden_provider_request_seconds{provider, result}}: how long those calls took;
 *   <li>{@code warden_provider_circuit_state{provider, state=closed|open|half_open}}: 1 for the
 *       current state of the provider's breaker, 0 for the others.
 * </ul>
 *
 * <p>Every series is registered at startup for both configured providers, so each one is exposed
 * from zero. A call skipped because the breaker is open is not an attempt of that provider.
 */
@Component
public class ProviderMetrics {

    public static final String ATTEMPTS = "warden.notification.attempts";
    public static final String REQUESTS = "warden.provider.request";
    public static final String CIRCUIT_STATE = "warden.provider.circuit.state";
    public static final String PROVIDER_TAG = "provider";
    public static final String RESULT_TAG = "result";
    public static final String STATE_TAG = "state";

    /** The breaker states a provider can be in; the others are never configured. */
    public static final List<CircuitBreaker.State> EXPOSED_STATES =
            List.of(CircuitBreaker.State.CLOSED, CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);

    private final Map<String, Map<ProviderResult, Counter>> attempts = new HashMap<>();
    private final Map<String, Map<ProviderResult, Timer>> requests = new HashMap<>();

    public ProviderMetrics(MeterRegistry meterRegistry, EmailProviders providers, ProviderCircuitBreakers breakers) {
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        Objects.requireNonNull(breakers, "breakers");
        for (String provider :
                List.of(providers.primary().name(), providers.secondary().name())) {
            Map<ProviderResult, Counter> attemptsOf = new EnumMap<>(ProviderResult.class);
            Map<ProviderResult, Timer> requestsOf = new EnumMap<>(ProviderResult.class);
            for (ProviderResult result : ProviderResult.values()) {
                attemptsOf.put(
                        result,
                        Counter.builder(ATTEMPTS)
                                .description("Calls made to an e-mail provider by result")
                                .tags(PROVIDER_TAG, provider, RESULT_TAG, resultTag(result))
                                .register(meterRegistry));
                requestsOf.put(
                        result,
                        Timer.builder(REQUESTS)
                                .description("Duration of the calls made to an e-mail provider by result")
                                .tags(PROVIDER_TAG, provider, RESULT_TAG, resultTag(result))
                                .register(meterRegistry));
            }
            attempts.put(provider, attemptsOf);
            requests.put(provider, requestsOf);
            for (CircuitBreaker.State state : EXPOSED_STATES) {
                Gauge.builder(CIRCUIT_STATE, breakers, current -> current.state(provider) == state ? 1 : 0)
                        .description("1 for the current state of the provider's circuit breaker, 0 otherwise")
                        .tags(PROVIDER_TAG, provider, STATE_TAG, stateTag(state))
                        .strongReference(true)
                        .register(meterRegistry);
            }
        }
    }

    /** Records one call to {@code provider} that answered {@code result} in {@code nanos} ns. */
    public void recordCall(String provider, ProviderResult result, long nanos) {
        Map<ProviderResult, Counter> attemptsOf = attempts.get(provider);
        if (attemptsOf == null) {
            return;
        }
        attemptsOf.get(result).increment();
        requests.get(provider).get(result).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** The tag value of a provider result, such as {@code transient_failure}. */
    public static String resultTag(ProviderResult result) {
        return result.name().toLowerCase(Locale.ROOT);
    }

    /** The tag value of a breaker state, such as {@code half_open}. */
    public static String stateTag(CircuitBreaker.State state) {
        return state.name().toLowerCase(Locale.ROOT);
    }
}
