package io.github.kaulinmindiola.warden.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.iam.domain.PasswordHash;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class BoundedPasswordHasherTest {

    /** Small parameters keep the unit tests fast; production parameters are checked in the IT. */
    private static Argon2PasswordEncoder argon2(int memoryKib, int iterations) {
        return new Argon2PasswordEncoder(16, 32, 1, memoryKib, iterations);
    }

    @Test
    void hashesAndVerifiesWithArgon2id() {
        BoundedPasswordHasher hasher = new BoundedPasswordHasher(argon2(1024, 1), 2);

        PasswordHash hash = hasher.hash("correct horse battery staple");

        assertThat(hash.value()).startsWith("$argon2id$");
        assertThat(hasher.matches("correct horse battery staple", hash)).isTrue();
        assertThat(hasher.matches("wrong horse battery staple", hash)).isFalse();
        assertThat(hasher.hash("correct horse battery staple")).isNotEqualTo(hash);
    }

    @Test
    void theDummyVerificationAlwaysFails() {
        BoundedPasswordHasher hasher = new BoundedPasswordHasher(argon2(1024, 1), 2);

        assertThat(hasher.matchesDummy("any password at all")).isFalse();
        assertThat(hasher.matchesDummy("")).isFalse();
    }

    @Test
    void aHashMadeWithWeakerParametersNeedsRehash() {
        PasswordHash weak = new BoundedPasswordHasher(argon2(1024, 1), 1).hash("correct horse battery staple");
        BoundedPasswordHasher stronger = new BoundedPasswordHasher(argon2(2048, 2), 1);
        BoundedPasswordHasher same = new BoundedPasswordHasher(argon2(1024, 1), 1);

        assertThat(stronger.needsRehash(weak)).isTrue();
        assertThat(same.needsRehash(weak)).isFalse();
        assertThat(stronger.matches("correct horse battery staple", weak)).isTrue();
    }

    @Test
    void neverRunsMoreHashesAtOnceThanAllowed() throws Exception {
        ConcurrencyProbe probe = new ConcurrencyProbe();
        BoundedPasswordHasher hasher = new BoundedPasswordHasher(probe, 2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<PasswordHash>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String password = "password-number-" + i;
                results.add(executor.submit(() -> hasher.hash(password)));
            }
            for (Future<PasswordHash> result : results) {
                result.get();
            }
        }

        assertThat(probe.calls.get()).isEqualTo(9);
        assertThat(probe.maxConcurrent.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void rejectsAConcurrencyLimitBelowOne() {
        assertThatThrownBy(() -> new BoundedPasswordHasher(argon2(1024, 1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Argon2Properties(19456, 2, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Encoder that records how many encode calls overlap. The first call is the dummy hash. */
    private static final class ConcurrencyProbe implements PasswordEncoder {

        private final AtomicInteger current = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            calls.incrementAndGet();
            int now = current.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                current.decrementAndGet();
            }
            return "{probe}" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encodedPassword.equals("{probe}" + rawPassword);
        }
    }
}
