package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.workspace.application.MemberView;
import java.time.Instant;
import java.util.UUID;

/**
 * A workspace member in API responses (SDD V08).
 *
 * @param userId the member
 * @param email the member's e-mail address
 * @param role the member's role
 * @param joinedAt when the member joined, in UTC
 */
public record MemberResponse(UUID userId, String email, Role role, Instant joinedAt) {

    static MemberResponse from(MemberView view) {
        return new MemberResponse(view.userId(), view.email(), view.role(), view.joinedAt());
    }
}
