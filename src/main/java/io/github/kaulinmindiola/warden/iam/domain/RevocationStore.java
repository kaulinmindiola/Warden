package io.github.kaulinmindiola.warden.iam.domain;

import java.util.UUID;

/**
 * Fast lookup of revoked sessions, consulted on every authenticated request (REQ-FUNC-018,
 * ADR-007). PostgreSQL stays the source of truth; this store only answers quickly.
 */
public interface RevocationStore {

    /** What the store knows about a session. */
    enum State {
        /** The session is revoked: reject its tokens. */
        REVOKED,
        /** The store is reachable and has no revocation for the session. */
        NOT_REVOKED,
        /** The store could not answer; the caller must ask PostgreSQL. */
        UNKNOWN
    }

    /**
     * Records that the session is revoked, for at least the lifetime of its access tokens. Never
     * throws: a failure leaves PostgreSQL as the only record until reconciliation.
     */
    void markRevoked(UUID sessionId);

    /** What the store knows about the session; never throws. */
    State state(UUID sessionId);
}
