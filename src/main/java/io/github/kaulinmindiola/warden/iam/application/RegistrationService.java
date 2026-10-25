package io.github.kaulinmindiola.warden.iam.application;

import io.github.kaulinmindiola.warden.iam.EmailVerificationRequested;
import io.github.kaulinmindiola.warden.iam.RegistrationAttemptedOnExistingAccount;
import io.github.kaulinmindiola.warden.iam.domain.EmailAddress;
import io.github.kaulinmindiola.warden.iam.domain.InvalidEmailException;
import io.github.kaulinmindiola.warden.iam.domain.InvalidPasswordException;
import io.github.kaulinmindiola.warden.iam.domain.OneTimeToken;
import io.github.kaulinmindiola.warden.iam.domain.PasswordHash;
import io.github.kaulinmindiola.warden.iam.domain.PasswordHasher;
import io.github.kaulinmindiola.warden.iam.domain.PasswordPolicy;
import io.github.kaulinmindiola.warden.iam.domain.SupportedLocale;
import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.iam.infrastructure.OneTimeTokenRepository;
import io.github.kaulinmindiola.warden.iam.infrastructure.UserAccountRepository;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers accounts without revealing whether an e-mail already has one (UC-01, REQ-FUNC-001,
 * REQ-FUNC-002, BR-007).
 *
 * <ol>
 *   <li>Validates the e-mail, the password policy and the locale, reporting every invalid field.
 *   <li>Always hashes the password, before looking the e-mail up and outside the transaction, so
 *       both outcomes cost the same work and no connection is held during Argon2.
 *   <li>New e-mail: creates the unverified account and a verification token, stores only the
 *       token hash and publishes {@code iam.EmailVerificationRequested} with the token sealed.
 *   <li>Existing e-mail: publishes {@code iam.RegistrationAttemptedOnExistingAccount} for the
 *       holder. A concurrent registration of the same e-mail ends the same way.
 * </ol>
 */
@Service
public class RegistrationService {

    static final String REQUIRED = "is required";
    static final String MALFORMED_EMAIL = "must be a well-formed e-mail address";
    static final String PASSWORD_LENGTH =
            "must be between " + PasswordPolicy.MIN_LENGTH + " and " + PasswordPolicy.MAX_LENGTH + " characters";
    static final String PASSWORD_TOO_COMMON = "is too common";
    static final String UNSUPPORTED_LOCALE = "must be one of: es, en";

    private final UserAccountRepository accounts;
    private final OneTimeTokenRepository tokens;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher passwordHasher;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final SecretCipher secretCipher;
    private final OutboxPublisher outbox;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RegistrationService(
            UserAccountRepository accounts,
            OneTimeTokenRepository tokens,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            SecretCipher secretCipher,
            OutboxPublisher outbox,
            UuidV7Generator ids,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.passwordPolicy = passwordPolicy;
        this.passwordHasher = passwordHasher;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.secretCipher = secretCipher;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Registers an account or notifies the holder of the existing one. Both outcomes return
     * normally and look the same to the caller.
     *
     * @param rawEmail e-mail as submitted
     * @param password password as submitted
     * @param localeTag {@code es}, {@code en} or {@code null} for the default
     * @throws InvalidRegistrationException if any field is invalid
     */
    public void register(String rawEmail, String password, String localeTag) {
        Map<String, String> violations = new LinkedHashMap<>();
        EmailAddress email = null;
        if (rawEmail == null) {
            violations.put("email", REQUIRED);
        } else {
            try {
                email = EmailAddress.of(rawEmail);
            } catch (InvalidEmailException malformed) {
                violations.put("email", MALFORMED_EMAIL);
            }
        }
        if (password == null) {
            violations.put("password", REQUIRED);
        } else {
            try {
                passwordPolicy.check(password);
            } catch (InvalidPasswordException rejected) {
                violations.put("password", reasonFor(rejected.reason()));
            }
        }
        SupportedLocale locale = SupportedLocale.DEFAULT;
        if (localeTag != null) {
            locale = SupportedLocale.fromTag(localeTag).orElse(null);
            if (locale == null) {
                violations.put("locale", UNSUPPORTED_LOCALE);
            }
        }
        if (!violations.isEmpty()) {
            throw new InvalidRegistrationException(violations);
        }

        PasswordHash hash = passwordHasher.hash(password);
        EmailAddress validEmail = email;
        SupportedLocale validLocale = locale;
        try {
            transactions.executeWithoutResult(status -> registerOrNotify(validEmail, hash, validLocale));
        } catch (DataIntegrityViolationException concurrentRegistration) {
            transactions.executeWithoutResult(
                    status -> notifyHolder(accounts.findByEmail(validEmail).orElseThrow(() -> concurrentRegistration)));
        }
    }

    private void registerOrNotify(EmailAddress email, PasswordHash hash, SupportedLocale locale) {
        UserAccount existing = accounts.findByEmail(email).orElse(null);
        if (existing != null) {
            notifyHolder(existing);
            return;
        }
        Instant now = clock.instant();
        UserAccount account = UserAccount.register(ids.next(), email, hash, locale, now);
        accounts.persist(account);
        accounts.flush();

        String token = tokenGenerator.newOneTimeToken();
        OneTimeToken verification = OneTimeToken.issue(
                ids.next(),
                account.id(),
                OneTimeToken.Purpose.EMAIL_VERIFICATION,
                TokenHash.of(tokenHasher.sha256(token)),
                now);
        tokens.persist(verification);

        SealedSecret secret = secretCipher.seal(token, verification.expiresAt());
        outbox.publish(
                EmailVerificationRequested.TYPE,
                null,
                new EmailVerificationRequested(recipientOf(account), secret, verification.expiresAt()));
    }

    private void notifyHolder(UserAccount holder) {
        outbox.publish(
                RegistrationAttemptedOnExistingAccount.TYPE,
                null,
                new RegistrationAttemptedOnExistingAccount(recipientOf(holder), clock.instant()));
    }

    private static Recipient recipientOf(UserAccount account) {
        return new Recipient(
                account.id(),
                account.email().value(),
                account.registrationLocale().tag());
    }

    private static String reasonFor(InvalidPasswordException.Reason reason) {
        return switch (reason) {
            case TOO_SHORT, TOO_LONG -> PASSWORD_LENGTH;
            case TOO_COMMON -> PASSWORD_TOO_COMMON;
        };
    }
}
