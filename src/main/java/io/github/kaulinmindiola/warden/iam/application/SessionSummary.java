package io.github.kaulinmindiola.warden.iam.application;

import java.time.Instant;
import java.util.UUID;

/**
 * What a user sees of one of their active sessions (DEC-011).
 *
 * @param id session id
 * @param createdAt when the session was created by a login
 * @param lastUsedAt last login or refresh of the session
 * @param userAgent sanitized, truncated User-Agent summary, or {@code null}
 * @param current whether the request was made with this session
 */
public record SessionSummary(UUID id, Instant createdAt, Instant lastUsedAt, String userAgent, boolean current) {}
