package io.github.kaulinmindiola.warden.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.MutableClock;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    @Test
    @Verifies("REQ-INT-007")
    void generatesVersion7WithTheRfcVariant() {
        UUID id = new UuidV7Generator(Clock.systemUTC()).next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheClockMillisecondsInTheFirst48Bits() {
        Instant now = Instant.parse("2026-01-02T03:04:05.123Z");
        UUID id = new UuidV7Generator(Clock.fixed(now, ZoneOffset.UTC)).next();

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
    }

    @Test
    void identifiersFromLaterMillisecondsSortAfterEarlierOnes() {
        MutableClock clock = new MutableClock();
        UuidV7Generator generator = new UuidV7Generator(clock);

        UUID first = generator.next();
        clock.advance(Duration.ofMillis(1));
        UUID second = generator.next();

        assertThat(second.toString()).isGreaterThan(first.toString());
    }

    @Test
    void generatesDistinctIdentifiersWithinTheSameMillisecond() {
        UuidV7Generator generator = new UuidV7Generator(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        Set<UUID> ids = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.next());
        }

        assertThat(ids).hasSize(10_000);
    }
}
