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
 * Confirms an e-mail address with a verification token (UC-02, REQ-FUNC-006). The token is
 * consumed and the account verified in one transaction. Every reason a token cannot be used ends
 * in the same {@link InvalidOneTimeTokenException}.
 */
@Service
public class EmailVerificationService {

    private final OneTimeTokenConsumer tokenConsumer;
    private final UserAccountRepository accounts;
    private final TokenHasher tokenHasher;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public EmailVerificationService(
            OneTimeTokenConsumer tokenConsumer,
            UserAccountRepository accounts,
            TokenHasher tokenHasher,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tokenConsumer = tokenConsumer;
        this.accounts = accounts;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Verifies the e-mail of the account the token belongs to.
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
}
