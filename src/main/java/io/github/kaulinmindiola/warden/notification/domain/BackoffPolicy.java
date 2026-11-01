package io.github.kaulinmindiola.warden.notification.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * The wait before the next attempt of a delivery (REQ-REL-006, SDD V14-A5):
 * {@code min(max, initial · multiplier^(n−1)) · uniform(1 − jitter, 1 + jitter)} after the n-th
 * failed attempt. With the SRS values the waits are 1, 2, 4 and 8 s, each within ±50 %.
 *
 * <p>The random source is injected, so tests can pin it; a jitter of 0 removes it altogether.
 */
public final class BackoffPolicy {

    private final Duration initial;
    private final double multiplier;
    private final Duration max;
    private final double jitter;
    private final DoubleSupplier random;

    /**
     * @param jitter the fraction of the base wait added or removed at random, in [0, 1)
     * @param random values in [0, 1)
     * @throws IllegalArgumentException for a non-positive initial wait, a multiplier below 1, a
     *     maximum below the initial wait or a jitter outside [0, 1)
     */
    public BackoffPolicy(Duration initial, double multiplier, Duration max, double jitter, DoubleSupplier random) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(max, "max");
        this.random = Objects.requireNonNull(random, "random");
        if (initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("The initial wait must be positive");
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException("The multiplier must be at least 1");
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("The maximum wait cannot be below the initial wait");
        }
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("The jitter must be in [0, 1)");
        }
        this.initial = initial;
        this.multiplier = multiplier;
        this.max = max;
        this.jitter = jitter;
    }

    /**
     * The wait without jitter after the {@code attempt}-th failed attempt.
     *
     * @throws IllegalArgumentException if {@code attempt} is below 1
     */
    public Duration base(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempts are counted from 1");
        }
        double millis = initial.toMillis() * Math.pow(multiplier, attempt - 1);
        return millis >= max.toMillis() ? max : Duration.ofMillis(Math.round(millis));
    }

    /** The wait after the {@code attempt}-th failed attempt, jitter included. */
    public Duration delay(int attempt) {
        long base = base(attempt).toMillis();
        double factor = 1 + jitter * (2 * random.getAsDouble() - 1);
        return Duration.ofMillis(Math.round(base * factor));
    }
}
