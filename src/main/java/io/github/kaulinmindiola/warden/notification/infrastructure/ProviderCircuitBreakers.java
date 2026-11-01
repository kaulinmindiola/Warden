package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.concurrent.TimeUnit;

/**
 * One Resilience4j circuit breaker per provider, shared by every delivery of this instance
 * (REQ-REL-004, ADR-009). Resilience4j core only, without its Spring Boot starter (POC-001).
 *
 * <p>Callers ask for permission, make the call themselves and record its result: a transient
 * failure counts as a failure; an acceptance and a permanent rejection count as successes, since a
 * provider that rejects an invalid address is working.
 */
public class ProviderCircuitBreakers {

    /** Recorded for transient failures; carries no stack trace and is never thrown. */
    private static final RuntimeException TRANSIENT_FAILURE =
            new RuntimeException("Transient provider failure", null, false, false) {};

    private final CircuitBreakerRegistry registry;

    public ProviderCircuitBreakers(CircuitBreakerProperties properties) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.probeCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build());
    }

    /** Whether a call to the provider may be made now; when open, no call is allowed. */
    public boolean tryAcquire(String provider) {
        return breaker(provider).tryAcquirePermission();
    }

    /** Records the outcome of a permitted call that took {@code nanos} nanoseconds. */
    public void record(String provider, boolean transientFailure, long nanos) {
        CircuitBreaker breaker = breaker(provider);
        if (transientFailure) {
            breaker.onError(nanos, TimeUnit.NANOSECONDS, TRANSIENT_FAILURE);
        } else {
            breaker.onSuccess(nanos, TimeUnit.NANOSECONDS);
        }
    }

    /** The current state, for metrics and tests. */
    public CircuitBreaker.State state(String provider) {
        return breaker(provider).getState();
    }

    /**
     * Closes every breaker and forgets the calls it recorded. For tests that share one application
     * context, so that the failures one test provokes do not open a breaker for the next.
     */
    public void reset() {
        registry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    /** The configuration every breaker uses. */
    public CircuitBreakerConfig config() {
        return registry.getDefaultConfig();
    }

    private CircuitBreaker breaker(String provider) {
        return registry.circuitBreaker(provider);
    }
}
