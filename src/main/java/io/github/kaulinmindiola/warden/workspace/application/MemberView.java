package io.github.kaulinmindiola.warden.workspace.application;

import io.github.kaulinmindiola.warden.shared.security.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * A member of a workspace as seen by the other members (REQ-FUNC-037).
 *
 * @param userId the member
 * @param email the member's e-mail address
 * @param role the member's role
 * @param joinedAt when the member joined
 */
public record MemberView(UUID userId, String email, Role role, Instant joinedAt) {}
