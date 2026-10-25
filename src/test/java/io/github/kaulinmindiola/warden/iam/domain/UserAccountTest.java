package io.github.kaulinmindiola.warden.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserAccountTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private final UserAccount account = UserAccount.register(
            UUID.randomUUID(),
            EmailAddress.of("ana@mail.com"),
            new PasswordHash("$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA"),
            SupportedLocale.EN,
            NOW);

    @Test
    void aNewAccountHasAnUnverifiedEmail() {
        assertThat(account.isEmailVerified()).isFalse();
        assertThat(account.emailVerifiedAt()).isNull();
        assertThat(account.registrationLocale()).isEqualTo(SupportedLocale.EN);
        assertThat(account.createdAt()).isEqualTo(NOW);
    }

    @Test
    void verifyingTheEmailKeepsTheFirstVerificationTime() {
        account.verifyEmail(NOW.plus(Duration.ofHours(1)));
        account.verifyEmail(NOW.plus(Duration.ofHours(2)));

        assertThat(account.isEmailVerified()).isTrue();
        assertThat(account.emailVerifiedAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void changingThePasswordReplacesTheHash() {
        PasswordHash newHash = new PasswordHash("$argon2id$v=19$m=19456,t=2,p=1$bmV3$bmV3aGFzaA");

        account.changePassword(newHash);

        assertThat(account.passwordHash()).isEqualTo(newHash);
    }

    @Test
    void localesAreRestrictedToSpanishAndEnglish() {
        assertThat(SupportedLocale.fromTag("es")).contains(SupportedLocale.ES);
        assertThat(SupportedLocale.fromTag("en")).contains(SupportedLocale.EN);
        assertThat(SupportedLocale.fromTag("fr")).isEmpty();
        assertThat(SupportedLocale.fromTag("ES")).isEmpty();
        assertThat(SupportedLocale.DEFAULT).isEqualTo(SupportedLocale.ES);
        assertThat(new PasswordHash("x").toString()).doesNotContain("x]");
    }
}
