package io.github.kaulinmindiola.warden.iam.application;

import io.github.kaulinmindiola.warden.iam.RefreshTokenReuseDetected;
import io.github.kaulinmindiola.warden.iam.domain.AuthSession;
import io.github.kaulinmindiola.warden.iam.domain.RefreshToken;
import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.iam.infrastructure.AuthSessionRepository;
import io.github.kaulinmindiola.warden.iam.infrastructure.IssuedAccessToken;
import io.github.kaulinmindiola.warden.iam.infrastructure.JwtIssuer;
import io.github.kaulinmindiola.warden.iam.infrastructure.RefreshTokenConsumer;
import io.github.kaulinmindiola.warden.iam.infrastructure.RefreshTokenRepository;
import io.github.kaulinmindiola.warden.iam.infrastructure.UserAccountRepository;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimitPolicy;
import io.github.kaulinmindiola.warden.shared.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rotates refresh tokens and detects their reuse (UC-04, REQ-FUNC-011 to REQ-FUNC-014, SDD
 * V14-A1).
 *
 * <ol>
 *   <li>Consumes the refresh limit of the client IP before looking at the token (REQ-SEC-009),
 *       so guessing tokens costs the same whether they exist or not.
 *   <li>Marks the presented token used with a conditional update. If it wins and its session is
 *       active, a new refresh token is stored and the session's last use updated; an inactive
 *       session rolls the transaction back, leaving the token unused.
 *   <li>If the update finds no unused, unexpired token, and the token exists, was already used
 *       and its session is still active, this is a reuse: the session is revoked conditionally
 *       and, only if this call revoked it, {@code iam.RefreshTokenReuseDetected} is published and
 *       audited. The revocation is committed even though the response is 401.
 *   <li>Unknown or expired tokens are simply rejected; expiry never raises an alert.
 * </ol>
 *
 * Every rejection ends in the same {@link InvalidRefreshTokenException}. After the commit, a
 * revoked session is marked in the revocation store, so its access tokens fail on the next
 * request.
 */
@Service
public class RefreshService {

    private final RefreshTokenConsumer refreshTokenConsumer;
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final UserAccountRepository accounts;
    private final RevocationStore revocationStore;
    private final OutboxPublisher outbox;
    private final AuditLog auditLog;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final JwtIssuer jwtIssuer;
    private final RateLimiter rateLimiter;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RefreshService(
            RefreshTokenConsumer refreshTokenConsumer,
            AuthSessionRepository sessions,
            RefreshTokenRepository refreshTokens,
            UserAccountRepository accounts,
            RevocationStore revocationStore,
            OutboxPublisher outbox,
            AuditLog auditLog,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            JwtIssuer jwtIssuer,
            RateLimiter rateLimiter,
            UuidV7Generator ids,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.refreshTokenConsumer = refreshTokenConsumer;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.revocationStore = revocationStore;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.jwtIssuer = jwtIssuer;
        this.rateLimiter = rateLimiter;
        this.ids = ids;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token of the same session.
     *
     * @param presented the refresh token from the request body
     * @param clientIp address of the client, as seen by the server
     * @return the new tokens
     * @throws InvalidRefreshTokenException if the token is missing, malformed, unknown, used,
     *     expired or belongs to an inactive session
     * @throws io.github.kaulinmindiola.warden.shared.web.ProblemException {@code rate-limited} if
     *     the IP sent too many refresh requests recently
     */
    public LoginResult refresh(String presented, String clientIp) {
        rateLimiter.consume(RateLimitPolicy.REFRESH_PER_IP, clientIp).requireAllowed();
        if (presented == null || !presented.startsWith(SecureTokenGenerator.REFRESH_TOKEN_PREFIX)) {
            throw new InvalidRefreshTokenException();
        }
        TokenHash presentedHash = TokenHash.of(tokenHasher.sha256(presented));
        String next = tokenGenerator.newRefreshToken();

        Outcome outcome = transactions.execute(status -> rotateOrDetectReuse(status, presentedHash, next));

        if (outcome.revokedSessionId() != null) {
            revocationStore.markRevoked(outcome.revokedSessionId());
        }
        Rotation rotation = outcome.rotation();
        if (rotation == null) {
            throw new InvalidRefreshTokenException();
        }
        IssuedAccessToken accessToken = jwtIssuer.issue(rotation.userId(), rotation.sessionId());
        long refreshExpiresIn = Duration.between(rotation.rotatedAt(), rotation.refreshExpiresAt())
                .toSeconds();
        return new LoginResult(accessToken.value(), accessToken.expiresInSeconds(), next, refreshExpiresIn);
    }

    private Outcome rotateOrDetectReuse(TransactionStatus status, TokenHash presentedHash, String next) {
        Instant now = clock.instant();
        Optional<UUID> consumedSession = refreshTokenConsumer.consume(presentedHash, now);
        if (consumedSession.isEmpty()) {
            return new Outcome(null, revokeOnReuse(presentedHash, now));
        }
        AuthSession session = sessions.findById(consumedSession.get()).orElse(null);
        if (session == null || !session.isActive(now)) {
            status.setRollbackOnly();
            return new Outcome(null, null);
        }
        RefreshToken refresh = RefreshToken.issue(ids.next(), session, TokenHash.of(tokenHasher.sha256(next)), now);
        refreshTokens.persist(refresh);
        session.markUsed(now);
        return new Outcome(new Rotation(session.userId(), session.id(), now, refresh.expiresAt()), null);
    }

    /**
     * Handles a token the conditional update did not accept.
     *
     * @return the session revoked by this call because of a reuse, or {@code null}
     */
    private UUID revokeOnReuse(TokenHash presentedHash, Instant now) {
        RefreshToken presented = refreshTokens.findByHash(presentedHash).orElse(null);
        if (presented == null || presented.usedAt() == null) {
            return null;
        }
        AuthSession session = sessions.findById(presented.sessionId()).orElse(null);
        if (session == null || !session.isActive(now)) {
            return null;
        }
        if (sessions.revokeIfActive(session.id(), session.userId(), AuthSession.RevokeReason.REUSE, now) != 1) {
            return null;
        }
        UserAccount holder = accounts.findById(session.userId()).orElseThrow();
        outbox.publish(
                RefreshTokenReuseDetected.TYPE,
                null,
                new RefreshTokenReuseDetected(EmailVerificationIssuer.recipientOf(holder), session.id(), now));
        auditLog.recordSuccess(new AuditEntry(
                AuditAction.REFRESH_TOKEN_REUSE_DETECTED,
                session.userId(),
                null,
                Map.of("sessionId", session.id().toString())));
        return session.id();
    }

    private record Rotation(UUID userId, UUID sessionId, Instant rotatedAt, Instant refreshExpiresAt) {}

    /** Result of the transaction: a rotation, or a rejection that may have revoked a session. */
    private record Outcome(Rotation rotation, UUID revokedSessionId) {}
}
