package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.PasswordHash;
import io.github.kaulinmindiola.warden.iam.domain.PasswordHasher;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link PasswordHasher} that lets at most {@code maxConcurrency} hash computations run at once
 * (SDD V16, V23). With virtual threads, waiting for a permit is cheap; running unbounded Argon2
 * computations would exhaust memory under a burst of logins.
 *
 * <p>The dummy hash is computed once, at construction, from a random password that is not kept.
 */
public class BoundedPasswordHasher implements PasswordHasher {

    private final PasswordEncoder encoder;
    private final Semaphore permits;
    private final PasswordHash dummyHash;

    public BoundedPasswordHasher(PasswordEncoder encoder, int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.permits = new Semaphore(maxConcurrency, true);
        this.dummyHash = hash(randomPassword());
    }

    @Override
    public PasswordHash hash(String rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword");
        return new PasswordHash(bounded(() -> encoder.encode(rawPassword)));
    }

    @Override
    public boolean matches(String rawPassword, PasswordHash hash) {
        Objects.requireNonNull(rawPassword, "rawPassword");
        Objects.requireNonNull(hash, "hash");
        return bounded(() -> encoder.matches(rawPassword, hash.value()));
    }

    @Override
    public boolean needsRehash(PasswordHash hash) {
        Objects.requireNonNull(hash, "hash");
        return encoder.upgradeEncoding(hash.value());
    }

    @Override
    public boolean matchesDummy(String rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword");
        bounded(() -> encoder.matches(rawPassword, dummyHash.value()));
        return false;
    }

    private <T> T bounded(Supplier<T> work) {
        try {
            permits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to hash a password", exception);
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
