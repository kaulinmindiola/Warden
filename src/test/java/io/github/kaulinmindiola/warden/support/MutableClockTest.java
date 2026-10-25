package io.github.kaulinmindiola.warden.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MutableClockTest {

    @Test
    void standsStillUntilAdvanced() {
        MutableClock clock = new MutableClock();
        Instant start = clock.instant();

        assertThat(clock.instant()).isEqualTo(start);

        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(clock.instant()).isEqualTo(start.plus(Duration.ofHours(24).plusSeconds(1)));
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void setsAnInstantTruncatedToMicroseconds() {
        MutableClock clock = new MutableClock();

        clock.setInstant(Instant.parse("2026-01-01T00:00:00.123456789Z"));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
    }

    @Test
    void zonedViewsShareTheCurrentInstant() {
        MutableClock clock = new MutableClock();
        Clock bogota = clock.withZone(ZoneId.of("America/Bogota"));

        clock.advance(Duration.ofMinutes(30));

        assertThat(bogota.instant()).isEqualTo(clock.instant());
        assertThat(bogota.getZone()).isEqualTo(ZoneId.of("America/Bogota"));
    }

    @Test
    void resetReturnsToTheCurrentSystemTime() {
        MutableClock clock = new MutableClock();
        clock.setInstant(Instant.EPOCH);
        Instant beforeReset = Instant.now().minusSeconds(1);

        clock.reset();

        assertThat(clock.instant()).isAfter(beforeReset);
    }
}
