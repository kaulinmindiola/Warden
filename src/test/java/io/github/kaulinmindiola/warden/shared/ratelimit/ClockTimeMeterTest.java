package io.github.kaulinmindiola.warden.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ClockTimeMeterTest {

    private final MutableClock clock = new MutableClock();
    private final ClockTimeMeter timeMeter = new ClockTimeMeter(clock);

    @Test
    void readsTheApplicationClockInNanosecondsSinceTheEpoch() {
        clock.setInstant(Instant.parse("2026-10-29T12:00:00.000001Z"));

        assertThat(timeMeter.currentTimeNanos())
                .isEqualTo(Instant.parse("2026-10-29T12:00:00Z").getEpochSecond() * 1_000_000_000L + 1_000L);
    }

    @Test
    void followsTheClockWhenTheTestMovesIt() {
        long before = timeMeter.currentTimeNanos();

        clock.advance(Duration.ofSeconds(61));

        assertThat(timeMeter.currentTimeNanos() - before)
                .isEqualTo(Duration.ofSeconds(61).toNanos());
    }

    @Test
    void isWallClockBased() {
        assertThat(timeMeter.isWallClockBased()).isTrue();
    }
}
