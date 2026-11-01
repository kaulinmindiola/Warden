package io.github.kaulinmindiola.warden.iam.application;

import io.github.kaulinmindiola.warden.iam.PasswordChanged;
import io.github.kaulinmindiola.warden.iam.PasswordResetRequested;
import io.github.kaulinmindiola.warden.iam.domain.AuthSession;
import io.github.kaulinmindiola.warden.iam.domain.EmailAddress;
import io.github.kaulinmindiola.warden.iam.domain.InvalidEmailException;
import io.github.kaulinmindiola.warden.iam.domain.InvalidOneTimeTokenException;
import io.github.kaulinmindiola.warden.iam.domain.InvalidPasswordException;
import io.github.kaulinmindiola.warden.iam.domain.OneTimeToken;
import io.github.kaulinmindiola.warden.iam.domain.PasswordHash;
import io.github.kaulinmindiola.warden.iam.domain.PasswordHasher;
import io.github.kaulinmindiola.warden.iam.domain.PasswordPolicy;
import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.iam.infrastructure.AuthSessionRevoker;
import io.github.kaulinmindiola.warden.iam.infrastructure.IamMetrics;
import io.github.kaulinmindiola.warden.iam.infrastructure.OneTimeTokenConsumer;
import io.github.kaulinmindiola.warden.iam.infrastructure.OneTimeTokenRepository;
import io.github.kaulinmindiola.warden.iam.infrastructure.UserAccountRepository;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimitPolicy;
import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password recovery: requesting a reset link (UC-08, REQ-FUNC-019) and replacing the password
 * with it (UC-09, REQ-FUNC-020, REQ-FUNC-021).
 *
 * <p>A request is limited per client IP and per normalized e-mail, existing or not (REQ-SEC-008),
 * so the limits reveal nothing about accounts. The IP is counted before the e-mail is even read.
 *
 * <p>A request always ends the same way, whether or not the e-mail has an account (BR-007): the
 * token is generated in both cases; only for an existing account are the previous links
 * invalidated, the new one stored as a hash and {@code iam.PasswordResetRequested} published with
 * the token sealed (ADR-011).
 *
 * <p>A confirmation checks the password policy first, so a weak password leaves the link usable,
 * and hashes the new password outside the transaction. The transaction then consumes the token
 * conditionally, replaces the hash, verifies the e-mail, revokes every session of the user (DEC-022),
 * publishes {@code iam.PasswordChanged} and audits the reset. After the commit, each revoked session
 * is marked in the revocation store, so its access tokens fail on the next request (REQ-FUNC-018).
 */
@Service
public class PasswordResetService {

    static final String EMAIL = "email";
    static final String NEW_PASSWORD = "newPassword";

    private final UserAccountRepository accounts;
    private final OneTimeTokenRepository tokens;
    private final OneTimeTokenConsumer tokenConsumer;
    private final AuthSessionRevoker sessionRevoker;
    private final RevocationStore revocationStore;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher passwordHasher;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final SecretCipher secretCipher;
    private final OutboxPublisher outbox;
    private final AuditLog auditLog;
    private final RateLimiter rateLimiter;
    private final IamMetrics metrics;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public PasswordResetService(
            UserAccountRepository accounts,
            OneTimeTokenRepository tokens,
            OneTimeTokenConsumer tokenConsumer,
            AuthSessionRevoker sessionRevoker,
            RevocationStore revocationStore,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            SecretCipher secretCipher,
            OutboxPublisher outbox,
            AuditLog auditLog,
            RateLimiter rateLimiter,
            IamMetrics metrics,
            UuidV7Generator ids,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.tokenConsumer = tokenConsumer;
        this.sessionRevoker = sessionRevoker;
        this.revocationStore = revocationStore;
        this.passwordPolicy = passwordPolicy;
        this.passwordHasher = passwordHasher;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.secretCipher = secretCipher;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.ids = ids;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Sends a reset link if the e-mail has an account. Returns normally in both cases.
     *
     * @param rawEmail e-mail as submitted
     * @param clientIp address of the client, as seen by the server
     * @throws InvalidPasswordResetException if the e-mail is missing or malformed
     * @throws io.github.kaulinmindiola.warden.shared.web.ProblemException {@code rate-limited} if
     *     the IP or the e-mail sent too many reset requests recently
     */
    public void requestReset(String rawEmail, String clientIp) {
        rateLimiter.consume(RateLimitPolicy.PASSWORD_RESET_PER_IP, clientIp).requireAllowed();
        EmailAddress email = parseEmail(rawEmail);
        rateLimiter
                .consume(RateLimitPolicy.PASSWORD_RESET_PER_EMAIL, email.value())
                .requireAllowed();
        String token = tokenGenerator.newOneTimeToken();
        TokenHash hash = TokenHash.of(tokenHasher.sha256(token));
        transactions.executeWithoutResult(
                status -> accounts.findByEmail(email).ifPresent(account -> issue(account, token, hash)));
    }

    /**
     * Replaces the password of the account the token belongs to and revokes all its sessions.
     *
     * @param token the one-time token from the reset link
     * @param newPassword the new password
     * @throws InvalidPasswordResetException if the new password is missing or breaks the policy
     * @throws InvalidOneTimeTokenException if the token is missing, malformed, unknown, used,
     *     invalidated, expired or not a reset token
     */
    public void reset(String token, String newPassword) {
        checkNewPassword(newPassword);
        if (token == null || !token.startsWith(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)) {
            throw new InvalidOneTimeTokenException();
        }
        TokenHash hash = TokenHash.of(tokenHasher.sha256(token));
        PasswordHash newHash = passwordHasher.hash(newPassword);

        List<UUID> revokedSessions = transactions.execute(status -> {
            Instant now = clock.instant();
            UUID userId = tokenConsumer
                    .consume(hash, OneTimeToken.Purpose.PASSWORD_RESET, now)
                    .orElseThrow(InvalidOneTimeTokenException::new);
            UserAccount account = accounts.findById(userId).orElseThrow(InvalidOneTimeTokenException::new);
            account.changePassword(newHash);
            account.verifyEmail(now);
            List<UUID> revoked = sessionRevoker.revokeAll(userId, AuthSession.RevokeReason.PASSWORD_RESET, now);
            outbox.publish(
                    PasswordChanged.TYPE, null, new PasswordChanged(EmailVerificationIssuer.recipientOf(account), now));
            auditLog.recordSuccess(new AuditEntry(
                    AuditAction.PASSWORD_RESET,
                    userId,
                    null,
                    Map.of("revokedSessions", String.valueOf(revoked.size()))));
            return revoked;
        });

        revokedSessions.forEach(revocationStore::markRevoked);
        metrics.sessionsRevoked(AuthSession.RevokeReason.PASSWORD_RESET, revokedSessions.size());
    }

    private void issue(UserAccount account, String token, TokenHash hash) {
        Instant now = clock.instant();
        for (OneTimeToken previous : tokens.findByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNull(
                account.id(), OneTimeToken.Purpose.PASSWORD_RESET)) {
            previous.invalidate(now);
        }
        OneTimeToken reset =
                OneTimeToken.issue(ids.next(), account.id(), OneTimeToken.Purpose.PASSWORD_RESET, hash, now);
        tokens.persist(reset);

        SealedSecret secret = secretCipher.seal(token, reset.expiresAt());
        outbox.publish(
                PasswordResetRequested.TYPE,
                null,
                new PasswordResetRequested(EmailVerificationIssuer.recipientOf(account), secret, reset.expiresAt()));
    }

    private static EmailAddress parseEmail(String rawEmail) {
        if (rawEmail == null) {
            throw new InvalidPasswordResetException(Map.of(EMAIL, RegistrationService.REQUIRED));
        }
        try {
            return EmailAddress.of(rawEmail);
        } catch (InvalidEmailException malformed) {
            throw new InvalidPasswordResetException(Map.of(EMAIL, RegistrationService.MALFORMED_EMAIL));
        }
    }

    private void checkNewPassword(String newPassword) {
        Map<String, String> violations = new LinkedHashMap<>();
        if (newPassword == null) {
            violations.put(NEW_PASSWORD, RegistrationService.REQUIRED);
        } else {
            try {
                passwordPolicy.check(newPassword);
            } catch (InvalidPasswordException rejected) {
                violations.put(NEW_PASSWORD, reasonFor(rejected.reason()));
            }
        }
        if (!violations.isEmpty()) {
            throw new InvalidPasswordResetException(violations);
        }
    }

    private static String reasonFor(InvalidPasswordException.Reason reason) {
        return switch (reason) {
            case TOO_SHORT, TOO_LONG -> RegistrationService.PASSWORD_LENGTH;
            case TOO_COMMON -> RegistrationService.PASSWORD_TOO_COMMON;
        };
    }
}
