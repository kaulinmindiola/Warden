package io.github.kaulinmindiola.warden.isolation;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Every workspace-scoped endpoint, with what the isolation suite and the role matrix need to call
 * it (SDD R7, REQ-MAINT-003). {@link WorkspaceEndpointRulesIT} fails the build when a handler under
 * {@value #WORKSPACE_ROUTE} is missing here.
 *
 * <p>To add an endpoint: one entry below, with the role its handler declares.
 */
public final class IsolationRegistry {

    /** Prefix of every workspace-scoped route; the variable name is the interceptor's contract. */
    public static final String WORKSPACE_ROUTE = ApiWebConfiguration.API_PREFIX + "/workspaces/{workspaceId}";

    /** Any route below {@code /workspaces/} with a path variable is workspace-scoped. */
    public static final String WORKSPACE_SCOPED_PREFIX = ApiWebConfiguration.API_PREFIX + "/workspaces/{";

    public static final List<WorkspaceEndpoint> ENDPOINTS = List.of(
            new WorkspaceEndpoint(
                    HttpMethod.GET,
                    WORKSPACE_ROUTE,
                    Role.VIEWER,
                    200,
                    workspaceId -> MockMvcRequestBuilders.get(WORKSPACE_ROUTE, workspaceId)),
            new WorkspaceEndpoint(
                    HttpMethod.GET,
                    WORKSPACE_ROUTE + "/members",
                    Role.VIEWER,
                    200,
                    workspaceId -> MockMvcRequestBuilders.get(WORKSPACE_ROUTE + "/members", workspaceId)));

    private IsolationRegistry() {}

    public static Optional<WorkspaceEndpoint> find(String key) {
        return ENDPOINTS.stream().filter(endpoint -> endpoint.key().equals(key)).findFirst();
    }
}
