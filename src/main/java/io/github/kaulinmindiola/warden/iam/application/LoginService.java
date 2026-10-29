package io.github.kaulinmindiola.warden.iam.application;

import io.github.kaulinmindiola.warden.iam.domain.AuthSession;
import io.github.kaulinmindiola.warden.iam.domain.EmailAddress;
import io.github.kaulinmindiola.warden.iam.domain.InvalidEmailException;
import io.github.kaulinmindiola.warden.iam.domain.PasswordHash;
import io.github.kaulinmindiola.warden.iam.domain.PasswordHasher;
import io.github.kaulinmindiola.warden.iam.domain.RefreshToken;
import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.iam.infrastructure.AuthSessionRepository;
import io.github.kaulinmindiola.warden.iam.infrastructure.IssuedAccessToken;
import io.github.kaulinmindiola.warden.iam.infrastructure.JwtIssuer;
import io.github.kaulinmindiola.warden.iam.infrastructure.RefreshTokenRepository;
import io.github.kaulinmindiola.warden.iam.infrastructure.UserAccountRepository;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimitPolicy;
import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authenticates with e-mail and password and starts a session (UC-03, SDD V14-A2).
 *
 * <ol>
 *   <li>Applies the rate limits before any work: the failed-login limit of the normalized e-mail
 *       is checked without consuming it (REQ-SEC-006), so a blocked e-mail gets 429 even with the
 *       right password, and the login limit of the client IP is consumed (REQ-SEC-007). Unknown
 *       e-mails are limited exactly like existing ones, so a 429 reveals nothing (BR-007).
 *   <li>Verifies the password with Argon2 against the account's hash, or against the dummy hash
 *       when the e-mail is unknown, malformed or missing, so every case costs the same work.
 *   <li>Any failure consumes the failed-login limit of a well-formed e-mail, is audited in its own
 *       transaction and ends in the same {@link InvalidCredentialsException}.
 *   <li>On success, computes a stronger hash if the parameters have changed, then, in one
 *       transaction, stores the rehash, the session, the refresh token hash and the audit record.
 *   <li>Signs the access token after the commit.
 * </ol>
 *
 * An unverified e-mail does not prevent login (REQ-FUNC-008).
 */
@Service
public class LoginService {

    private final UserAccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher passwordHasher;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final JwtIssuer jwtIssuer;
    private final AuditLog auditLog;
    private final RateLimiter rateLimiter;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public LoginService(
            UserAccountRepository accounts,
            AuthSessionRepository sessions,
            RefreshTokenRepository refreshTokens,
            PasswordHasher passwordHasher,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            JwtIssuer jwtIssuer,
            AuditLog auditLog,
            RateLimiter rateLimiter,
            UuidV7Generator ids,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.passwordHasher = passwordHasher;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.jwtIssuer = jwtIssuer;
        this.auditLog = auditLog;
        this.rateLimiter = rateLimiter;
        this.ids = ids;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Logs in and returns a new session's tokens.
     *
     * @param rawEmail e-mail as submitted
     * @param password password as submitted
     * @param userAgent the {@code User-Agent} header, or {@code null}
     * @param clientIp address of the client, as seen by the server
     * @throws InvalidCredentialsException if the e-mail or the password is wrong, for any reason
     * @throws io.github.kaulinmindiola.warden.shared.web.ProblemException {@code rate-limited} if
     *     the e-mail has too many recent failures or the IP too many recent logins
     */
    public LoginResult login(String rawEmail, String password, String userAgent, String clientIp) {
        EmailAddress email = parseEmail(rawEmail);
        if (email != null) {
            rateLimiter
                    .check(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, email.value())
                    .requireAllowed();
        }
        rateLimiter.consume(RateLimitPolicy.LOGIN_PER_IP, clientIp).requireAllowed();

        String candidate = password == null ? "" : password;
        UserAccount account = email == null ? null : accounts.findByEmail(email).orElse(null);
        boolean authenticated = account != null
                ? passwordHasher.matches(candidate, account.passwordHash())
                : passwordHasher.matchesDummy(candidate);
        if (!authenticated) {
            if (email != null) {
                rateLimiter.consume(RateLimitPolicy.LOGIN_FAILURES_PER_EMAIL, email.value());
            }
            auditLog.recordFailure(new AuditEntry(
                    AuditAction.LOGIN,
                    account == null ? null : account.id(),
                    null,
                    Map.of("reason", "invalid_credentials")));
            throw new InvalidCredentialsException();
        }

        PasswordHash rehashed =
                passwordHasher.needsRehash(account.passwordHash()) ? passwordHasher.hash(candidate) : null;
        String refreshToken = tokenGenerator.newRefreshToken();
        StartedSession started =
                transactions.execute(status -> startSession(account.id(), rehashed, refreshToken, userAgent));

        IssuedAccessToken accessToken = jwtIssuer.issue(account.id(), started.sessionId());
        long refreshExpiresIn = Duration.between(started.startedAt(), started.refreshExpiresAt())
                .toSeconds();
        return new LoginResult(accessToken.value(), accessToken.expiresInSeconds(), refreshToken, refreshExpiresIn);
    }

    /** The normalized e-mail, or {@code null} when it is missing or malformed. */
    private static EmailAddress parseEmail(String rawEmail) {
        if (rawEmail == null) {
            return null;
        }
        try {
            return EmailAddress.of(rawEmail);
        } catch (InvalidEmailException malformed) {
            return null;
        }
    }

    private StartedSession startSession(UUID userId, PasswordHash rehashed, String refreshToken, String userAgent) {
        Instant now = clock.instant();
        if (rehashed != null) {
            accounts.findById(userId).orElseThrow().changePassword(rehashed);
        }
        AuthSession session = AuthSession.start(ids.next(), userId, now, userAgent);
        sessions.persist(session);
        RefreshToken refresh =
                RefreshToken.issue(ids.next(), session, TokenHash.of(tokenHasher.sha256(refreshToken)), now);
        refreshTokens.persist(refresh);
        auditLog.recordSuccess(AuditEntry.of(AuditAction.LOGIN, userId, null));
        return new StartedSession(session.id(), now, refresh.expiresAt());
    }

    private record StartedSession(UUID sessionId, Instant startedAt, Instant refreshExpiresAt) {}
}
