package io.github.kaulinmindiola.warden.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimiter.Decision;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimiterDecisionTest {

    @Test
    void aPermitHasNoRetryAfterAndRequireAllowedPasses() {
        Decision permit = Decision.permit();

        assertThat(permit.allowed()).isTrue();
        assertThat(permit.retryAfter()).isZero();
        assertThatCode(permit::requireAllowed).doesNotThrowAnyException();
    }

    @Test
    void aRejectionBecomesTheRateLimitedProblemWithItsRetryAfter() {
        Decision rejection = Decision.reject(Duration.ofMillis(1500));

        assertThatThrownBy(rejection::requireAllowed).isInstanceOfSatisfying(ProblemException.class, problem -> {
            assertThat(problem.type()).isEqualTo(ProblemType.RATE_LIMITED);
            assertThat(problem.retryAfter()).contains(Duration.ofMillis(1500));
            assertThat(problem.retryAfterSeconds()).hasValue(2L);
        });
    }

    @Test
    void aRejectionNeedsAPositiveRetryAfter() {
        assertThatThrownBy(() -> Decision.reject(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Decision.reject(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Decision.reject(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anAllowedDecisionCannotCarryARetryAfter() {
        assertThatThrownBy(() -> new Decision(true, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
