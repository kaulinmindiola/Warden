package io.github.kaulinmindiola.warden.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.shared.logging.LogFields;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** {@code userId} and {@code workspaceId} in the MDC for the length of a request (REQ-OBS-004). */
class LogContextFilterTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final CurrentUser USER = new CurrentUser(UUID.randomUUID(), UUID.randomUUID());

    private final LogContextFilter filter = new LogContextFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    @Verifies("REQ-OBS-004")
    void anAuthenticatedWorkspaceRequestCarriesBothFieldsWhileItIsHandledOnly() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new CurrentUserAuthentication(USER));
        Map<String, String> seen = new HashMap<>();

        filter.doFilter(
                request("/api/v1/workspaces/" + WORKSPACE + "/notes"),
                new MockHttpServletResponse(),
                (request, response) -> {
                    seen.put(LogFields.USER_ID, MDC.get(LogFields.USER_ID));
                    seen.put(LogFields.WORKSPACE_ID, MDC.get(LogFields.WORKSPACE_ID));
                });

        assertThat(seen)
                .containsEntry(LogFields.USER_ID, USER.userId().toString())
                .containsEntry(LogFields.WORKSPACE_ID, WORKSPACE.toString());
        assertThat(MDC.get(LogFields.USER_ID)).isNull();
        assertThat(MDC.get(LogFields.WORKSPACE_ID)).isNull();
    }

    @Test
    void aPublicRequestCarriesNeither() throws Exception {
        Map<String, String> seen = new HashMap<>();

        filter.doFilter(request("/api/v1/auth/login"), new MockHttpServletResponse(), (request, response) -> {
            seen.put(LogFields.USER_ID, MDC.get(LogFields.USER_ID));
            seen.put(LogFields.WORKSPACE_ID, MDC.get(LogFields.WORKSPACE_ID));
        });

        assertThat(seen.get(LogFields.USER_ID)).isNull();
        assertThat(seen.get(LogFields.WORKSPACE_ID)).isNull();
    }

    @Test
    void theWorkspaceIdIsTakenOnlyFromAWellFormedWorkspacePath() {
        assertThat(LogContextFilter.workspaceIdOf("/api/v1/workspaces/" + WORKSPACE))
                .isEqualTo(WORKSPACE);
        assertThat(LogContextFilter.workspaceIdOf("/api/v1/workspaces/" + WORKSPACE + "/members/x"))
                .isEqualTo(WORKSPACE);
        assertThat(LogContextFilter.workspaceIdOf("/api/v1/workspaces")).isNull();
        assertThat(LogContextFilter.workspaceIdOf("/api/v1/workspaces/not-a-uuid/notes"))
                .isNull();
        assertThat(LogContextFilter.workspaceIdOf("/api/v1/auth/sessions/" + WORKSPACE))
                .isNull();
        assertThat(LogContextFilter.workspaceIdOf(null)).isNull();
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
