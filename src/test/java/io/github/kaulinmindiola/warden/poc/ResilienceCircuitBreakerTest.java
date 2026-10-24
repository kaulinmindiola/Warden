package io.github.kaulinmindiola.warden.poc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** POC-001: the provider circuit breaker opens after N failures without the Spring Boot starter. */
class ResilienceCircuitBreakerTest {

    @Test
    void opensAfterTheFailureThresholdAndRejectsFurtherCalls() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("poc-provider", config);

        for (int call = 0; call < 4; call++) {
            assertThatThrownBy(() -> breaker.executeRunnable(() -> {
                        throw new IllegalStateException("provider down");
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> breaker.executeRunnable(() -> {})).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void reportsWhetherTheOpenStateClockIsInjectable() {
        List<String> timeHooks = Arrays.stream(CircuitBreakerConfig.Builder.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).contains("clock") || name.contains("Timestamp"))
                .distinct()
                .sorted()
                .toList();

        System.out.println("POC-001 Resilience4j time hooks in CircuitBreakerConfig.Builder: " + timeHooks);
        assertThat(timeHooks).isNotNull();
    }
}
