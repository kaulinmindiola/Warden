package io.github.kaulinmindiola.warden.shared.security;

import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Workspace authorization, layer L1 of SDD V07. For handler methods annotated with {@link
 * RequiresWorkspaceRole}, resolves the caller's membership in the workspace of the route on every
 * request and fails closed (REQ-SEC-011):
 *
 * <ul>
 *   <li>malformed workspace id, no membership, deleted workspace or unknown role → 404, the same
 *       response as a workspace that does not exist;
 *   <li>role below the required one → 403 {@code insufficient-role};
 *   <li>memberships cannot be read → 503 with {@code Retry-After: 5};
 *   <li>no authenticated caller → 401.
 * </ul>
 *
 * The granted {@link WorkspaceAccess} is stored as a request attribute for the handler. Every
 * denial is counted in {@code warden_authz_denials_total{status=403|404|503}} (REQ-OBS-001).
 */
public class WorkspaceAuthorizationInterceptor implements HandlerInterceptor {

    /** Name of the path variable that carries the workspace id. */
    public static final String WORKSPACE_ID_VARIABLE = "workspaceId";

    /** Authorization denials by status. */
    public static final String DENIALS_METRIC = "warden.authz.denials";

    public static final String STATUS_TAG = "status";

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAuthorizationInterceptor.class);

    private final MembershipLookup membershipLookup;
    private final Map<ProblemType, Counter> denials = new EnumMap<>(ProblemType.class);

    /** An interceptor whose denials are counted in a registry of its own; for tests. */
    public WorkspaceAuthorizationInterceptor(MembershipLookup membershipLookup) {
        this(membershipLookup, new SimpleMeterRegistry());
    }

    public WorkspaceAuthorizationInterceptor(MembershipLookup membershipLookup, MeterRegistry meterRegistry) {
        this.membershipLookup = Objects.requireNonNull(membershipLookup, "membershipLookup");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        for (ProblemType denial :
                List.of(ProblemType.INSUFFICIENT_ROLE, ProblemType.NOT_FOUND, ProblemType.SERVICE_UNAVAILABLE)) {
            denials.put(
                    denial,
                    Counter.builder(DENIALS_METRIC)
                            .description("Workspace authorization denials by status")
                            .tag(STATUS_TAG, String.valueOf(denial.status().value()))
                            .register(meterRegistry));
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresWorkspaceRole required = method.getMethodAnnotation(RequiresWorkspaceRole.class);
        if (required == null) {
            return true;
        }
        try {
            authorize(request, method, required);
        } catch (ProblemException denied) {
            Counter counter = denials.get(denied.type());
            if (counter != null) {
                counter.increment();
            }
            throw denied;
        }
        return true;
    }

    private void authorize(HttpServletRequest request, HandlerMethod method, RequiresWorkspaceRole required) {
        UUID workspaceId = workspaceIdOf(request, method);
        CurrentUser user = currentUser();

        WorkspaceAccess access;
        try {
            access = membershipLookup
                    .find(workspaceId, user.userId())
                    .filter(found -> found.workspaceId().equals(workspaceId))
                    .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND));
        } catch (MembershipUnavailableException unavailable) {
            log.error("Workspace membership could not be resolved; the request is denied", unavailable);
            throw ProblemException.serviceUnavailable();
        }
        if (!access.role().isAtLeast(required.value())) {
            throw new ProblemException(ProblemType.INSUFFICIENT_ROLE);
        }
        request.setAttribute(WorkspaceAccess.REQUEST_ATTRIBUTE, access);
    }

    private static UUID workspaceIdOf(HttpServletRequest request, HandlerMethod method) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object raw = variables instanceof Map<?, ?> map ? map.get(WORKSPACE_ID_VARIABLE) : null;
        if (raw == null) {
            throw new IllegalStateException(method.getShortLogMessage()
                    + " requires a workspace role but its path has no {" + WORKSPACE_ID_VARIABLE + "} variable");
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException malformed) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        }
    }

    private static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        throw new ProblemException(ProblemType.UNAUTHENTICATED);
    }
}
