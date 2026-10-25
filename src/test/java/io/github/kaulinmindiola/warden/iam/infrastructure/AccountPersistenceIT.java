package io.github.kaulinmindiola.warden.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.iam.domain.EmailAddress;
import io.github.kaulinmindiola.warden.iam.domain.OneTimeToken;
import io.github.kaulinmindiola.warden.iam.domain.PasswordHash;
import io.github.kaulinmindiola.warden.iam.domain.PasswordHasher;
import io.github.kaulinmindiola.warden.iam.domain.SupportedLocale;
import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account and one-time token persistence against PostgreSQL, as the application role. */
class AccountPersistenceIT extends IntegrationTest {

    /** PostgreSQL SQLSTATE for unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final PasswordHash HASH = new PasswordHash("$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA");

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private OneTimeTokenRepository tokens;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private UuidV7Generator ids;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void storesAndLoadsAnAccountByNormalizedEmail() {
        Instant now = Instant.parse("2026-06-01T12:00:00.123456Z");
        clock.setInstant(now);
        String local = "ana-" + UUID.randomUUID();
        UserAccount account = UserAccount.register(
                ids.next(), EmailAddress.of(local + "@Mail.com"), HASH, SupportedLocale.EN, clock.instant());

        inTransaction(() -> accounts.persist(account));

        UserAccount loaded = transaction()
                .execute(status -> accounts.findByEmail(EmailAddress.of("  " + local.toUpperCase() + "@MAIL.COM "))
                        .orElseThrow());
        assertThat(loaded.id()).isEqualTo(account.id());
        assertThat(loaded.email().value()).isEqualTo(local + "@mail.com");
        assertThat(loaded.passwordHash()).isEqualTo(HASH);
        assertThat(loaded.isEmailVerified()).isFalse();
        assertThat(loaded.registrationLocale()).isEqualTo(SupportedLocale.EN);
        assertThat(loaded.createdAt()).isEqualTo(now);
    }

    @Test
    @Verifies("REQ-FUNC-004")
    void aSecondAccountWithTheSameNormalizedEmailIsRejectedByTheDatabase() {
        String local = "dup-" + UUID.randomUUID();
        inTransaction(() -> accounts.persist(UserAccount.register(
                ids.next(), EmailAddress.of(local + "@mail.com"), HASH, SupportedLocale.ES, clock.instant())));

        assertThatThrownBy(() -> inTransaction(() -> {
                    accounts.persist(UserAccount.register(
                            ids.next(),
                            EmailAddress.of(" " + local.toUpperCase() + "@Mail.COM"),
                            HASH,
                            SupportedLocale.ES,
                            clock.instant()));
                    accounts.flush();
                }))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .asInstanceOf(InstanceOfAssertFactories.type(SQLException.class))
                .extracting(SQLException::getSQLState)
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void storesOneTimeTokensByHashAndFindsTheActiveOnes() {
        UserAccount account = UserAccount.register(
                ids.next(),
                EmailAddress.of("tok-" + UUID.randomUUID() + "@mail.com"),
                HASH,
                SupportedLocale.ES,
                clock.instant());
        OneTimeToken invalidated = OneTimeToken.issue(
                ids.next(), account.id(), OneTimeToken.Purpose.EMAIL_VERIFICATION, randomHash(), clock.instant());
        OneTimeToken active = OneTimeToken.issue(
                ids.next(), account.id(), OneTimeToken.Purpose.EMAIL_VERIFICATION, randomHash(), clock.instant());
        OneTimeToken otherPurpose = OneTimeToken.issue(
                ids.next(), account.id(), OneTimeToken.Purpose.PASSWORD_RESET, randomHash(), clock.instant());

        inTransaction(() -> {
            accounts.persist(account);
            tokens.persist(invalidated);
            tokens.persist(active);
            tokens.persist(otherPurpose);
            invalidated.invalidate(clock.instant());
        });

        List<OneTimeToken> found = transaction()
                .execute(status -> tokens.findByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNull(
                        account.id(), OneTimeToken.Purpose.EMAIL_VERIFICATION));
        assertThat(found).extracting(OneTimeToken::id).containsExactly(active.id());
        assertThat(found.getFirst().hash()).isEqualTo(active.hash());
        assertThat(found.getFirst().expiresAt()).isEqualTo(active.expiresAt());
    }

    @Test
    @Verifies("REQ-SEC-003")
    void theConfiguredHasherUsesTheArgon2idParameters() {
        PasswordHash hash = passwordHasher.hash("correct horse battery staple");

        assertThat(hash.value()).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(passwordHasher.matches("correct horse battery staple", hash)).isTrue();
        assertThat(passwordHasher.needsRehash(hash)).isFalse();
    }

    private void inTransaction(Runnable work) {
        transaction().executeWithoutResult(status -> work.run());
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static TokenHash randomHash() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 0);
        UUID random = UUID.randomUUID();
        long most = random.getMostSignificantBits();
        long least = random.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (8 * i));
            bytes[8 + i] = (byte) (least >>> (8 * i));
        }
        return TokenHash.of(bytes);
    }
}
