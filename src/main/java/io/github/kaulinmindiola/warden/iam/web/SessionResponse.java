package io.github.kaulinmindiola.warden.iam.web;

import io.github.kaulinmindiola.warden.iam.application.SessionSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * One item of {@code GET /api/v1/auth/sessions} (DEC-011).
 *
 * @param id session id
 * @param createdAt creation time, UTC
 * @param lastUsedAt last login or refresh, UTC
 * @param userAgent sanitized, truncated User-Agent summary, or {@code null}
 * @param current whether the request was made with this session
 */
public record SessionResponse(UUID id, Instant createdAt, Instant lastUsedAt, String userAgent, boolean current) {

    static SessionResponse from(SessionSummary summary) {
        return new SessionResponse(
                summary.id(), summary.createdAt(), summary.lastUsedAt(), summary.userAgent(), summary.current());
    }
}
