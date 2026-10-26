package io.github.kaulinmindiola.warden.iam.application;

import io.github.kaulinmindiola.warden.iam.domain.AuthSession;
import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import io.github.kaulinmindiola.warden.iam.infrastructure.AuthSessionRepository;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The user's own sessions: listing (UC-06), terminating one (UC-07) and logging out (UC-05).
 *
 * <p>Revocations are conditional, so each one is audited exactly once. After the commit and
 * before returning, the session is marked in the revocation store, so the very next request with
 * one of its tokens is rejected (REQ-FUNC-018).
 */
@Service
public class SessionService {

    private final AuthSessionRepository sessions;
    private final RevocationStore revocationStore;
    private final AuditLog auditLog;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public SessionService(
            AuthSessionRepository sessions,
            RevocationStore revocationStore,
            AuditLog auditLog,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.revocationStore = revocationStore;
        this.auditLog = auditLog;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Active sessions of the caller, flagging the one the request was made with (REQ-FUNC-016). */
    public Page<SessionSummary> listActive(CurrentUser user, Pageable pageable) {
        return sessions.findByUserIdAndRevokedAtIsNullAndAbsoluteExpiresAtAfter(
                        user.userId(), clock.instant(), pageable)
                .map(session -> new SessionSummary(
                        session.id(),
                        session.createdAt(),
                        session.lastUsedAt(),
                        session.userAgentSummary(),
                        session.id().equals(user.sessionId())));
    }

    /**
     * Revokes one of the caller's sessions (REQ-FUNC-017). Revoking an already revoked own
     * session succeeds without a second audit record.
     *
     * @throws SessionNotFoundException if the session does not exist or belongs to someone else
     */
    public void terminate(CurrentUser user, UUID sessionId) {
        transactions.executeWithoutResult(status -> {
            if (!revoke(user, sessionId, AuthSession.RevokeReason.TERMINATED, AuditAction.SESSION_TERMINATED)
                    && !sessions.existsByIdAndUserId(sessionId, user.userId())) {
                throw new SessionNotFoundException();
            }
        });
        revocationStore.markRevoked(sessionId);
    }

    /** Revokes the session the request was made with (REQ-FUNC-015). Never fails if already revoked. */
    public void logout(CurrentUser user) {
        transactions.executeWithoutResult(
                status -> revoke(user, user.sessionId(), AuthSession.RevokeReason.LOGOUT, AuditAction.LOGOUT));
        revocationStore.markRevoked(user.sessionId());
    }

    /** @return whether this call revoked the session */
    private boolean revoke(CurrentUser user, UUID sessionId, AuthSession.RevokeReason reason, AuditAction action) {
        Instant now = clock.instant();
        boolean revoked = sessions.revokeIfActive(sessionId, user.userId(), reason, now) == 1;
        if (revoked) {
            auditLog.recordSuccess(
                    new AuditEntry(action, user.userId(), null, Map.of("sessionId", sessionId.toString())));
        }
        return revoked;
    }
}
