package io.github.kaulinmindiola.warden.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.shared.security.RequiresWorkspaceRole;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Build gates over the real handler mappings: R7 / FF-04 (every workspace handler is in the
 * isolation registry, REQ-MAINT-003) and FF-05 (every workspace handler declares the role the
 * registry expects, REQ-SEC-011).
 */
class WorkspaceEndpointRulesIT extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** Workspace-scoped handlers by {@code METHOD pattern}. */
    private final Map<String, HandlerMethod> workspaceHandlers = new TreeMap<>();

    @BeforeEach
    void collectWorkspaceHandlers() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> handler :
                handlerMapping.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = handler.getKey().getMethodsCondition().getMethods();
            for (String pattern : handler.getKey().getPatternValues()) {
                if (!pattern.startsWith(IsolationRegistry.WORKSPACE_SCOPED_PREFIX)) {
                    continue;
                }
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    workspaceHandlers.put(method.name() + " " + pattern, handler.getValue());
                }
            }
        }
    }

    @Test
    @Verifies("REQ-MAINT-003")
    void everyWorkspaceHandlerIsInTheIsolationRegistry() {
        Set<String> unregistered = new TreeSet<>(workspaceHandlers.keySet());
        IsolationRegistry.ENDPOINTS.forEach(endpoint -> unregistered.remove(endpoint.key()));

        System.out.println("R7 workspace handlers: " + workspaceHandlers.keySet());
        assertThat(workspaceHandlers).isNotEmpty();
        assertThat(unregistered)
                .as("workspace handlers missing from IsolationRegistry (R7)")
                .isEmpty();
    }

    @Test
    @Verifies("REQ-MAINT-003")
    void everyRegisteredEndpointExistsAndUsesTheWorkspaceIdVariable() {
        List<String> problems = new ArrayList<>();
        for (WorkspaceEndpoint endpoint : IsolationRegistry.ENDPOINTS) {
            if (!workspaceHandlers.containsKey(endpoint.key())) {
                problems.add(endpoint.key() + " has no handler");
            }
        }
        for (String key : workspaceHandlers.keySet()) {
            String pattern = key.substring(key.indexOf(' ') + 1);
            if (!pattern.equals(IsolationRegistry.WORKSPACE_ROUTE)
                    && !pattern.startsWith(IsolationRegistry.WORKSPACE_ROUTE + "/")) {
                problems.add(key + " does not use the {workspaceId} variable");
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    @Verifies({"REQ-SEC-011", "REQ-FUNC-024"})
    void everyWorkspaceHandlerDeclaresTheRegisteredRole() {
        List<String> problems = new ArrayList<>();
        workspaceHandlers.forEach((key, handler) -> {
            RequiresWorkspaceRole declared = handler.getMethodAnnotation(RequiresWorkspaceRole.class);
            if (declared == null) {
                problems.add(key + " has no @RequiresWorkspaceRole (FF-05)");
                return;
            }
            IsolationRegistry.find(key)
                    .filter(endpoint -> endpoint.minimumRole() != declared.value())
                    .ifPresent(endpoint -> problems.add(key + " declares " + declared.value()
                            + " but the registry expects " + endpoint.minimumRole()));
        });

        assertThat(problems).isEmpty();
    }
}
