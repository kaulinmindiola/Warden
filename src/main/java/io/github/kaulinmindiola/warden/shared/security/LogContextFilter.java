package io.github.kaulinmindiola.warden.shared.security;

import io.github.kaulinmindiola.warden.shared.logging.LogFields;
import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts {@code userId} and {@code workspaceId} in the MDC for the rest of the request, so every log
 * line written while it is handled carries them (REQ-OBS-004, SDD V19). Runs in the security
 * filter chain once the access token has been validated.
 *
 * <ul>
 *   <li>{@code userId}: the authenticated user, absent on public endpoints;
 *   <li>{@code workspaceId}: the workspace of a {@code /api/v1/workspaces/{id}…} path, whether or not
 *       the caller turns out to be a member; absent when the path names none or the id is malformed.
 * </ul>
 *
 * Both are removed when the request ends, so they never leak into the next request served by the
 * same thread. Neither is a secret or a personal datum (REQ-OBS-005).
 */
public class LogContextFilter extends OncePerRequestFilter {

    private static final String WORKSPACES_PATH = ApiWebConfiguration.API_PREFIX + "/workspaces/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID userId = currentUserId();
        UUID workspaceId = workspaceIdOf(request.getRequestURI());
        if (userId != null) {
            MDC.put(LogFields.USER_ID, userId.toString());
        }
        if (workspaceId != null) {
            MDC.put(LogFields.WORKSPACE_ID, workspaceId.toString());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LogFields.USER_ID);
            MDC.remove(LogFields.WORKSPACE_ID);
        }
    }

    private static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser user
                ? user.userId()
                : null;
    }

    /** The workspace id of a workspace path, or {@code null}. */
    static UUID workspaceIdOf(String uri) {
        if (uri == null || !uri.startsWith(WORKSPACES_PATH)) {
            return null;
        }
        String rest = uri.substring(WORKSPACES_PATH.length());
        int slash = rest.indexOf('/');
        String candidate = slash < 0 ? rest : rest.substring(0, slash);
        if (candidate.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
