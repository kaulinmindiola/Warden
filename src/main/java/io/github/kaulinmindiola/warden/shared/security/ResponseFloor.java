package io.github.kaulinmindiola.warden.shared.security;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Makes an action last at least a fixed time, so its duration does not reveal which branch ran
 * (BR-007, SDD V14-A3, ADR-019).
 *
 * <p>Elapsed time is measured with a monotonic clock, independent of the business {@link
 * java.time.Clock}. The wait happens in a {@code finally} block: an action that fails is delayed
 * like one that succeeds. An action slower than the floor is not delayed further. On virtual
 * threads the wait does not hold a carrier thread.
 */
public class ResponseFloor {

    /** Waits for a duration; replaced in unit tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Duration floor;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    /**
     * @param floor minimum duration; zero disables the wait
     * @throws IllegalArgumentException if the floor is negative
     */
    public ResponseFloor(Duration floor) {
        this(floor, System::nanoTime, Thread::sleep);
    }

    ResponseFloor(Duration floor, LongSupplier nanoTime, Sleeper sleeper) {
        Objects.requireNonNull(floor, "floor");
        if (floor.isNegative()) {
            throw new IllegalArgumentException("The response floor must not be negative");
        }
        this.floor = floor;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** Runs the action and returns its result no sooner than the floor after it started. */
    public <T> T call(Supplier<T> action) {
        long start = nanoTime.getAsLong();
        try {
            return action.get();
        } finally {
            waitForRemainder(start);
        }
    }

    /** Runs the action and returns no sooner than the floor after it started. */
    public void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    public Duration floor() {
        return floor;
    }

    private void waitForRemainder(long start) {
        Duration remaining = floor.minusNanos(nanoTime.getAsLong() - start);
        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }
        try {
            sleeper.sleep(remaining);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
