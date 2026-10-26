package io.github.kaulinmindiola.warden.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ResponseFloorTest {

    private static final Duration FLOOR = Duration.ofMillis(250);

    private final List<Duration> sleeps = new ArrayList<>();

    @Test
    void aFastActionWaitsForTheRemainderOfTheFloor() {
        ResponseFloor floor = new ResponseFloor(FLOOR, elapsed(Duration.ofMillis(40)), sleeps::add);

        String result = floor.call(() -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(sleeps).containsExactly(Duration.ofMillis(210));
    }

    @Test
    void aSlowActionIsNotDelayed() {
        ResponseFloor floor = new ResponseFloor(FLOOR, elapsed(Duration.ofMillis(300)), sleeps::add);

        floor.run(() -> {});

        assertThat(sleeps).isEmpty();
    }

    @Test
    void anActionThatTakesExactlyTheFloorIsNotDelayed() {
        ResponseFloor floor = new ResponseFloor(FLOOR, elapsed(FLOOR), sleeps::add);

        floor.run(() -> {});

        assertThat(sleeps).isEmpty();
    }

    @Test
    void aFailingActionStillWaitsAndItsExceptionPropagates() {
        ResponseFloor floor = new ResponseFloor(FLOOR, elapsed(Duration.ofMillis(10)), sleeps::add);

        assertThatThrownBy(() -> floor.run(() -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(sleeps).containsExactly(Duration.ofMillis(240));
    }

    @Test
    void aZeroFloorNeverSleeps() {
        ResponseFloor floor = new ResponseFloor(Duration.ZERO, elapsed(Duration.ZERO), sleeps::add);

        floor.run(() -> {});

        assertThat(sleeps).isEmpty();
    }

    @Test
    void aNegativeFloorIsRejected() {
        assertThatThrownBy(() -> new ResponseFloor(Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResponseFloorProperties(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInterruptedWaitKeepsTheResultAndTheInterruptFlag() {
        ResponseFloor floor = new ResponseFloor(FLOOR, elapsed(Duration.ZERO), duration -> {
            throw new InterruptedException();
        });

        String result = floor.call(() -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void theFloorDefaultsTo250Milliseconds() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of()));

        ResponseFloorProperties properties = binder.bindOrCreate("warden.security", ResponseFloorProperties.class);

        assertThat(properties.responseFloor()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void theFloorIsConfigurable() {
        Binder binder =
                new Binder(new MapConfigurationPropertySource(Map.of("warden.security.response-floor", "400ms")));

        ResponseFloorProperties properties = binder.bindOrCreate("warden.security", ResponseFloorProperties.class);

        assertThat(properties.responseFloor()).isEqualTo(Duration.ofMillis(400));
    }

    /** A monotonic clock that reads 0 at the start and {@code elapsed} afterwards. */
    private static LongSupplier elapsed(Duration elapsed) {
        AtomicInteger readings = new AtomicInteger();
        return () -> readings.getAndIncrement() == 0 ? 0L : elapsed.toNanos();
    }
}
