package io.github.kaulinmindiola.warden.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Every workspace authorization denial is counted by its status (REQ-OBS-001, SDD V19). */
class WorkspaceAuthorizationMetricsTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final CurrentUser USER = new CurrentUser(UUID.randomUUID(), UUID.randomUUID());

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WorkspaceAuthorizationInterceptorTest.FakeMembershipLookup lookup =
            new WorkspaceAuthorizationInterceptorTest.FakeMembershipLookup();
    private final WorkspaceAuthorizationInterceptor interceptor =
            new WorkspaceAuthorizationInterceptor(lookup, registry);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new CurrentUserAuthentication(USER));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Verifies("REQ-OBS-001")
    void everyStatusIsExposedFromZero() {
        assertThat(denials("403")).isZero();
        assertThat(denials("404")).isZero();
        assertThat(denials("503")).isZero();
    }

    @Test
    @Verifies("REQ-OBS-001")
    void aNonMemberIsCountedAs404() {
        lookup.result = Optional.empty();

        deny();

        assertThat(denials("404")).isEqualTo(1);
        assertThat(denials("403") + denials("503")).isZero();
    }

    @Test
    @Verifies("REQ-OBS-001")
    void aRoleBelowTheMinimumIsCountedAs403() {
        lookup.result = Optional.of(new WorkspaceAccess(WORKSPACE, Role.VIEWER));

        deny();

        assertThat(denials("403")).isEqualTo(1);
        assertThat(denials("404") + denials("503")).isZero();
    }

    @Test
    @Verifies("REQ-OBS-001")
    void anUnavailableLookupIsCountedAs503() {
        lookup.failure = new MembershipUnavailableException("database down");

        deny();

        assertThat(denials("503")).isEqualTo(1);
        assertThat(denials("403") + denials("404")).isZero();
    }

    @Test
    void aGrantedRequestAndAnUnauthenticatedOneAreNotDenialsOfAuthorization() throws Exception {
        lookup.result = Optional.of(new WorkspaceAccess(WORKSPACE, Role.OWNER));
        assertThat(interceptor.preHandle(request(), new MockHttpServletResponse(), handler()))
                .isTrue();
        SecurityContextHolder.clearContext();

        deny();

        assertThat(denials("403") + denials("404") + denials("503")).isZero();
    }

    private void deny() {
        assertThatThrownBy(() -> interceptor.preHandle(request(), new MockHttpServletResponse(), handler()))
                .isInstanceOf(ProblemException.class);
    }

    private double denials(String status) {
        return registry.get(WorkspaceAuthorizationInterceptor.DENIALS_METRIC)
                .tag(WorkspaceAuthorizationInterceptor.STATUS_TAG, status)
                .counter()
                .count();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of(WorkspaceAuthorizationInterceptor.WORKSPACE_ID_VARIABLE, WORKSPACE.toString()));
        return request;
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(
                new WorkspaceAuthorizationInterceptorTest.Handlers(),
                WorkspaceAuthorizationInterceptorTest.Handlers.class.getDeclaredMethod("member"));
    }
}
