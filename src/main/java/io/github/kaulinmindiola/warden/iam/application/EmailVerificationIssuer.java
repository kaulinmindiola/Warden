package io.github.kaulinmindiola.warden.iam.application;

import io.github.kaulinmindiola.warden.iam.EmailVerificationRequested;
import io.github.kaulinmindiola.warden.iam.domain.OneTimeToken;
import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.iam.infrastructure.OneTimeTokenRepository;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues e-mail verification tokens for registration and resend (REQ-FUNC-005, REQ-FUNC-007).
 *
 * <p>Invalidates the account's previous verification tokens, so at most one is active (SDD V05),
 * stores only the hash of the new token and publishes {@code iam.EmailVerificationRequested} with
 * the token sealed (ADR-011). Runs in the caller's transaction.
 */
@Component
class EmailVerificationIssuer {

    private final OneTimeTokenRepository tokens;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final SecretCipher secretCipher;
    private final OutboxPublisher outbox;
    private final UuidV7Generator ids;

    EmailVerificationIssuer(
            OneTimeTokenRepository tokens,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            SecretCipher secretCipher,
            OutboxPublisher outbox,
            UuidV7Generator ids) {
        this.tokens = tokens;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.secretCipher = secretCipher;
        this.outbox = outbox;
        this.ids = ids;
    }

    /**
     * Replaces the account's active verification tokens with a new one.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issueFor(UserAccount account, Instant now) {
        for (OneTimeToken previous : tokens.findByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNull(
                account.id(), OneTimeToken.Purpose.EMAIL_VERIFICATION)) {
            previous.invalidate(now);
        }

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

    /** The account holder as a notification recipient, in their registration locale (ADR-017). */
    static Recipient recipientOf(UserAccount account) {
        return new Recipient(
                account.id(),
                account.email().value(),
                account.registrationLocale().tag());
    }
}
