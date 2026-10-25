package io.github.kaulinmindiola.warden.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Generates RFC 9562 version 7 UUIDs: 48 bits of Unix epoch milliseconds from the injected clock
 * followed by random bits. Identifiers generated in later milliseconds sort after earlier ones,
 * which keeps B-tree index inserts local; order within the same millisecond is random.
 *
 * <p>Not sequential and not guessable (REQ-INT-007, MUC-01). Thread-safe.
 */
public final class UuidV7Generator {

    private static final long VERSION_7 = 0x7L << 12;
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;
    private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private final Clock clock;
    private final RandomGenerator random;

    public UuidV7Generator(Clock clock) {
        this(clock, new SecureRandom());
    }

    UuidV7Generator(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    public UUID next() {
        long millis = clock.millis();
        long randomA = random.nextInt(1 << 12);
        long mostSignificant = (millis << 16) | VERSION_7 | randomA;
        long leastSignificant = (random.nextLong() & VARIANT_MASK) | VARIANT_RFC_9562;
        return new UUID(mostSignificant, leastSignificant);
    }
}
