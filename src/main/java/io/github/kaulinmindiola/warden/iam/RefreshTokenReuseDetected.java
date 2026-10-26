package io.github.kaulinmindiola.warden.iam;

import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code iam.RefreshTokenReuseDetected} (SDD V10): a used refresh token was presented
 * again, so its session was revoked and its holder is warned (REQ-FUNC-012).
 *
 * @param recipient holder of the session, with their registration locale
 * @param sessionId the revoked session
 * @param occurredAt when the reuse was detected
 */
public record RefreshTokenReuseDetected(Recipient recipient, UUID sessionId, Instant occurredAt) {

    /** Event type stored in the outbox. */
    public static final String TYPE = "iam.RefreshTokenReuseDetected";
}
