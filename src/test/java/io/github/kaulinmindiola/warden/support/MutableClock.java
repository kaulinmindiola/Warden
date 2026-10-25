package io.github.kaulinmindiola.warden.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that stands still until a test moves it, so expiry and retention rules can be tested at
 * their exact boundaries (for example 24 h and 24 h + 1 s).
 *
 * <p>Thread-safe. Instants have microsecond precision, the precision of PostgreSQL
 * {@code timestamptz}, so a value read back from the database equals the one the clock produced.
 * Clocks returned by {@link #withZone(ZoneId)} share the same current instant.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock() {
        this(new AtomicReference<>(currentInstant()), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, Objects.requireNonNull(zone, "zone"));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    /** Moves the clock to the given instant, truncated to microseconds. */
    public void setInstant(Instant instant) {
        now.set(Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.MICROS));
    }

    /** Moves the clock forward (or backward, for a negative duration). */
    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        now.updateAndGet(current -> current.plus(duration).truncatedTo(ChronoUnit.MICROS));
    }

    /** Moves the clock back to the current system time. Called before every integration test. */
    public void reset() {
        now.set(currentInstant());
    }

    private static Instant currentInstant() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
