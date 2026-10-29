package io.github.kaulinmindiola.warden.iam.infrastructure;

import java.time.Instant;
import java.util.UUID;

/**
 * A recently revoked session, read from PostgreSQL to rebuild the Redis denylist (SDD V14-A7).
 *
 * @param sessionId the session
 * @param revokedAt when it was revoked
 */
public record RevokedSession(UUID sessionId, Instant revokedAt) {}
