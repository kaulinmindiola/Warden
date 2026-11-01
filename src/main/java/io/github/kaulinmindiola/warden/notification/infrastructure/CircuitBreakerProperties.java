package io.github.kaulinmindiola.warden.notification.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The circuit breaker of each e-mail provider (REQ-REL-004, DEC-042). The defaults are the SRS
 * values: the breaker opens when at least half of the last 20 calls, with a minimum of 10, were
 * transient failures; stays open 30 s; then lets 3 probe calls decide whether to close.
 *
 * @param slidingWindowSize how many recent calls are evaluated
 * @param minimumCalls how many calls the window needs before it can open the breaker
 * @param failureRateThreshold the percentage of transient failures that opens it
 * @param waitInOpenState how long it stays open before allowing probe calls
 * @param probeCalls how many probe calls decide, once the wait is over, whether it closes
 */
@ConfigurationProperties("warden.notification.circuit-breaker")
public record CircuitBreakerProperties(
        @DefaultValue("20") int slidingWindowSize,
        @DefaultValue("10") int minimumCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("30s") Duration waitInOpenState,
        @DefaultValue("3") int probeCalls) {

    public CircuitBreakerProperties {
        if (slidingWindowSize < 1 || minimumCalls < 1 || minimumCalls > slidingWindowSize || probeCalls < 1) {
            throw new IllegalArgumentException(
                    "warden.notification.circuit-breaker needs 1 <= minimum-calls <= sliding-window-size and probe-calls >= 1");
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
            throw new IllegalArgumentException(
                    "warden.notification.circuit-breaker.failure-rate-threshold must be in (0, 100]");
        }
        if (waitInOpenState == null || waitInOpenState.isZero() || waitInOpenState.isNegative()) {
            throw new IllegalArgumentException(
                    "warden.notification.circuit-breaker.wait-in-open-state must be positive");
        }
    }

    /** The SRS values, for code that runs without Spring. */
    public static CircuitBreakerProperties defaults() {
        return new CircuitBreakerProperties(20, 10, 50, Duration.ofSeconds(30), 3);
    }
}
