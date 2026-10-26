package io.github.kaulinmindiola.warden.iam.web;

import io.github.kaulinmindiola.warden.iam.application.SessionNotFoundException;
import io.github.kaulinmindiola.warden.iam.application.SessionService;
import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.web.PageQuery;
import io.github.kaulinmindiola.warden.shared.web.PageResponse;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's sessions (UC-05 to UC-07). Authenticated; served under {@code /api/v1}. */
@RestController
@RequestMapping("/auth")
class SessionController {

    private static final Sort MOST_RECENTLY_USED_FIRST = Sort.by(Sort.Order.desc("lastUsedAt"), Sort.Order.desc("id"));

    private final SessionService sessionService;

    SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** Lists the caller's active sessions, most recently used first (REQ-FUNC-016). */
    @GetMapping("/sessions")
    PageResponse<SessionResponse> list(@AuthenticationPrincipal CurrentUser user, @Valid PageQuery query) {
        return PageResponse.from(
                sessionService.listActive(user, query.toPageable(MOST_RECENTLY_USED_FIRST)), SessionResponse::from);
    }

    /** Revokes one of the caller's sessions: 204, or 404 if it is not theirs (REQ-FUNC-017). */
    @DeleteMapping("/sessions/{sessionId}")
    ResponseEntity<Void> terminate(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID sessionId) {
        try {
            sessionService.terminate(user, sessionId);
        } catch (SessionNotFoundException notFound) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    /** Revokes the current session: 204, also when it was already revoked (REQ-FUNC-015). */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal CurrentUser user) {
        sessionService.logout(user);
        return ResponseEntity.noContent().build();
    }
}
