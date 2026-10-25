package io.github.kaulinmindiola.warden.iam.application;

import io.github.kaulinmindiola.warden.iam.domain.InvalidOneTimeTokenException;
import io.github.kaulinmindiola.warden.iam.domain.OneTimeToken;
import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.iam.infrastructure.OneTimeTokenConsumer;
import io.github.kaulinmindiola.warden.iam.infrastructure.UserAccountRepository;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E-mail verification (UC-02): confirming an address with a token (REQ-FUNC-006) and resending
 * the link (REQ-FUNC-007).
 */
@Service
public class EmailVerificationService {

    private final OneTimeTokenConsumer tokenConsumer;
    private final UserAccountRepository accounts;
    private final EmailVerificationIssuer verificationIssuer;
    private final TokenHasher tokenHasher;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public EmailVerificationService(
            OneTimeTokenConsumer tokenConsumer,
            UserAccountRepository accounts,
            EmailVerificationIssuer verificationIssuer,
            TokenHasher tokenHasher,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tokenConsumer = tokenConsumer;
        this.accounts = accounts;
        this.verificationIssuer = verificationIssuer;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Verifies the e-mail of the account the token belongs to. The token is consumed and the
     * account verified in one transaction.
     *
     * @param token the token from the verification link
     * @throws InvalidOneTimeTokenException if the token is missing, malformed, unknown, used,
     *     invalidated or expired
     */
    public void verify(String token) {
        if (token == null || !token.startsWith(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)) {
            throw new InvalidOneTimeTokenException();
        }
        TokenHash hash = TokenHash.of(tokenHasher.sha256(token));
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            UUID userId = tokenConsumer
                    .consume(hash, OneTimeToken.Purpose.EMAIL_VERIFICATION, now)
                    .orElseThrow(InvalidOneTimeTokenException::new);
            UserAccount account = accounts.findById(userId).orElseThrow(InvalidOneTimeTokenException::new);
            account.verifyEmail(now);
        });
    }

    /**
     * Sends a new verification link to the authenticated user, invalidating the previous ones.
     *
     * @param userId the authenticated user
     * @throws EmailAlreadyVerifiedException if the e-mail is already verified
     */
    public void resend(UUID userId) {
        transactions.executeWithoutResult(status -> {
            UserAccount account = accounts.findById(userId)
                    .orElseThrow(() -> new IllegalStateException("The authenticated account does not exist"));
            if (account.isEmailVerified()) {
                throw new EmailAlreadyVerifiedException();
            }
            verificationIssuer.issueFor(account, clock.instant());
        });
    }
}
