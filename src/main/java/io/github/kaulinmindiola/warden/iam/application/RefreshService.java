package io.github.kaulinmindiola.warden.iam.application;

import io.github.kaulinmindiola.warden.iam.domain.AuthSession;
import io.github.kaulinmindiola.warden.iam.domain.RefreshToken;
import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import io.github.kaulinmindiola.warden.iam.infrastructure.AuthSessionRepository;
import io.github.kaulinmindiola.warden.iam.infrastructure.IssuedAccessToken;
import io.github.kaulinmindiola.warden.iam.infrastructure.JwtIssuer;
import io.github.kaulinmindiola.warden.iam.infrastructure.RefreshTokenConsumer;
import io.github.kaulinmindiola.warden.iam.infrastructure.RefreshTokenRepository;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rotates refresh tokens (UC-04, REQ-FUNC-011, SDD V14-A1).
 *
 * <p>In one transaction: marks the presented token used with a conditional update, checks that
 * its session is active, stores the hash of a new refresh token expiring at {@code min(now + 7
 * days, absolute expiry)} and updates the session's last use. An inactive session throws inside
 * the transaction, so the rollback leaves the presented token unused. The access token is signed
 * after the commit.
 */
@Service
public class RefreshService {

    private final RefreshTokenConsumer refreshTokenConsumer;
    private final AuthSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final JwtIssuer jwtIssuer;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RefreshService(
            RefreshTokenConsumer refreshTokenConsumer,
            AuthSessionRepository sessions,
            RefreshTokenRepository refreshTokens,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            JwtIssuer jwtIssuer,
            UuidV7Generator ids,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.refreshTokenConsumer = refreshTokenConsumer;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.jwtIssuer = jwtIssuer;
        this.ids = ids;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token of the same session.
     *
     * @param presented the refresh token from the request body
     * @return the new tokens
     * @throws InvalidRefreshTokenException if the token is missing, malformed, unknown, used,
     *     expired or belongs to an inactive session
     */
    public LoginResult refresh(String presented) {
        if (presented == null || !presented.startsWith(SecureTokenGenerator.REFRESH_TOKEN_PREFIX)) {
            throw new InvalidRefreshTokenException();
        }
        TokenHash presentedHash = TokenHash.of(tokenHasher.sha256(presented));
        String next = tokenGenerator.newRefreshToken();

        Rotation rotation = transactions.execute(status -> rotate(presentedHash, next));

        IssuedAccessToken accessToken = jwtIssuer.issue(rotation.userId(), rotation.sessionId());
        long refreshExpiresIn = Duration.between(rotation.rotatedAt(), rotation.refreshExpiresAt())
                .toSeconds();
        return new LoginResult(accessToken.value(), accessToken.expiresInSeconds(), next, refreshExpiresIn);
    }

    private Rotation rotate(TokenHash presentedHash, String next) {
        Instant now = clock.instant();
        UUID sessionId =
                refreshTokenConsumer.consume(presentedHash, now).orElseThrow(InvalidRefreshTokenException::new);
        AuthSession session = sessions.findById(sessionId).orElseThrow(InvalidRefreshTokenException::new);
        if (!session.isActive(now)) {
            throw new InvalidRefreshTokenException();
        }
        RefreshToken refresh = RefreshToken.issue(ids.next(), session, TokenHash.of(tokenHasher.sha256(next)), now);
        refreshTokens.persist(refresh);
        session.markUsed(now);
        return new Rotation(session.userId(), session.id(), now, refresh.expiresAt());
    }

    private record Rotation(UUID userId, UUID sessionId, Instant rotatedAt, Instant refreshExpiresAt) {}
}
