package io.github.kaulinmindiola.warden.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/** Waits between attempts (REQ-REL-006): base 1 s, factor 2, at most 60 s, ±50 % jitter. */
class BackoffPolicyTest {

    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Duration MINUTE = Duration.ofSeconds(60);

    @Test
    @Verifies("REQ-REL-006")
    void withoutJitterTheWaitsAre1248SecondsAndNeverAboveOneMinute() {
        BackoffPolicy policy = new BackoffPolicy(SECOND, 2, MINUTE, 0, () -> 0.9);

        assertThat(policy.delay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delay(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delay(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.delay(6)).isEqualTo(Duration.ofSeconds(32));
        assertThat(policy.delay(7)).isEqualTo(MINUTE);
        assertThat(policy.delay(60)).isEqualTo(MINUTE);
    }

    @Test
    @Verifies("REQ-REL-006")
    void theJitterReachesHalfTheBaseWaitAndStaysBelowOneAndAHalfTimesIt() {
        BackoffPolicy lowest = new BackoffPolicy(SECOND, 2, MINUTE, 0.5, () -> 0.0);
        BackoffPolicy middle = new BackoffPolicy(SECOND, 2, MINUTE, 0.5, () -> 0.5);
        BackoffPolicy highest = new BackoffPolicy(SECOND, 2, MINUTE, 0.5, () -> Math.nextDown(1.0));

        assertThat(lowest.delay(4)).isEqualTo(Duration.ofSeconds(4));
        assertThat(middle.delay(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(highest.delay(4)).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    @Verifies("REQ-REL-006")
    void withRandomJitterEveryWaitFallsWithinHalfOfItsBase() {
        BackoffPolicy policy = new BackoffPolicy(
                SECOND, 2, MINUTE, 0.5, () -> ThreadLocalRandom.current().nextDouble());

        for (int attempt = 1; attempt <= 8; attempt++) {
            long base = policy.base(attempt).toMillis();
            for (int sample = 0; sample < 500; sample++) {
                assertThat(policy.delay(attempt).toMillis()).isBetween(base / 2, base * 3 / 2);
            }
        }
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, 2, MINUTE, 0.5, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(SECOND, 0.5, MINUTE, 0.5, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(MINUTE, 2, SECOND, 0.5, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(SECOND, 2, MINUTE, 1, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(SECOND, 2, MINUTE, 0.5, () -> 0).base(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
