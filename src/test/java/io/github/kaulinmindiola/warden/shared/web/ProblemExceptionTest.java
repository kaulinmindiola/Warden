package io.github.kaulinmindiola.warden.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProblemExceptionTest {

    @Test
    void aPlainProblemHasNoRetryAfter() {
        ProblemException problem = new ProblemException(ProblemType.NOT_FOUND);

        assertThat(problem.retryAfter()).isEmpty();
        assertThat(problem.retryAfterSeconds()).isEmpty();
    }

    @Test
    void theRetryAfterIsRoundedUpToWholeSecondsAndIsAtLeastOne() {
        assertThat(seconds(Duration.ofSeconds(3))).isEqualTo(3L);
        assertThat(seconds(Duration.ofMillis(1500))).isEqualTo(2L);
        assertThat(seconds(Duration.ofSeconds(899).plusNanos(1))).isEqualTo(900L);
        assertThat(seconds(Duration.ofNanos(1))).isEqualTo(1L);
    }

    @Test
    void aRetryAfterProblemKeepsTheDefaultDetailOfItsType() {
        ProblemException problem = ProblemException.withRetryAfter(ProblemType.RATE_LIMITED, Duration.ofSeconds(5));

        assertThat(problem.type()).isEqualTo(ProblemType.RATE_LIMITED);
        assertThat(problem.detail()).isEqualTo(ProblemType.RATE_LIMITED.defaultDetail());
        assertThat(problem.violations()).isEmpty();
    }

    @Test
    void serviceUnavailableTellsTheClientToRetryAfterFiveSeconds() {
        ProblemException problem = ProblemException.serviceUnavailable();

        assertThat(problem.type()).isEqualTo(ProblemType.SERVICE_UNAVAILABLE);
        assertThat(problem.detail()).isEqualTo(ProblemType.SERVICE_UNAVAILABLE.defaultDetail());
        assertThat(problem.retryAfterSeconds()).hasValue(5L);
    }

    @Test
    void aNonPositiveRetryAfterIsRejected() {
        assertThatThrownBy(() -> ProblemException.withRetryAfter(ProblemType.RATE_LIMITED, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemException.withRetryAfter(ProblemType.RATE_LIMITED, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemException.withRetryAfter(ProblemType.RATE_LIMITED, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static long seconds(Duration retryAfter) {
        return ProblemException.withRetryAfter(ProblemType.RATE_LIMITED, retryAfter)
                .retryAfterSeconds()
                .orElseThrow();
    }
}
