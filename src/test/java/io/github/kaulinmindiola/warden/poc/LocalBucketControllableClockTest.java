package io.github.kaulinmindiola.warden.poc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** POC-001: the local (in-memory) Bucket4j bucket refills according to a test-controlled clock. */
class LocalBucketControllableClockTest {

    @Test
    void refillsOnlyWhenTheControlledClockAdvances() {
        MutableTimeMeter clock = new MutableTimeMeter();
        Bucket bucket = Bucket.builder()
                .withCustomTimePrecision(clock)
                .addLimit(limit -> limit.capacity(2).refillIntervally(2, Duration.ofMinutes(1)))
                .build();

        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isFalse();

        clock.advance(Duration.ofSeconds(61));

        assertThat(bucket.tryConsume(1)).isTrue();
    }
}
