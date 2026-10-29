package io.github.kaulinmindiola.warden.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** One test per branch of the fail-closed rules of SDD V12, with a fake membership lookup. */
class WorkspaceAuthorizationInterceptorTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final CurrentUser USER = new CurrentUser(UUID.randomUUID(), UUID.randomUUID());

    private final FakeMembershipLookup lookup = new FakeMembershipLookup();
    private final WorkspaceAuthorizationInterceptor interceptor = new WorkspaceAuthorizationInterceptor(lookup);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new CurrentUserAuthentication(USER));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aHandlerWithoutTheAnnotationIsNotChecked() throws Exception {
        assertThat(interceptor.preHandle(request(WORKSPACE.toString()), response(), handler("open")))
                .isTrue();
        assertThat(lookup.calls).isZero();
    }

    @Test
    void aHandlerThatIsNotAControllerMethodIsNotChecked() throws Exception {
        assertThat(interceptor.preHandle(request(WORKSPACE.toString()), response(), new Object()))
                .isTrue();
        assertThat(lookup.calls).isZero();
    }

    @Test
    @Verifies({"REQ-FUNC-022", "REQ-SEC-011"})
    void aNonMemberGets404() {
        lookup.result = Optional.empty();

        assertProblem(ProblemType.NOT_FOUND);
        assertThat(lookup.calls).isEqualTo(1);
        assertThat(lookup.lastUserId).isEqualTo(USER.userId());
    }

    @Test
    @Verifies("REQ-SEC-011")
    void aMembershipOfAnotherWorkspaceGets404() {
        lookup.result = Optional.of(new WorkspaceAccess(UUID.randomUUID(), Role.OWNER));

        assertProblem(ProblemType.NOT_FOUND);
    }

    @Test
    @Verifies({"REQ-FUNC-022", "REQ-SEC-011"})
    void aRoleBelowTheMinimumGets403() {
        lookup.result = Optional.of(new WorkspaceAccess(WORKSPACE, Role.VIEWER));

        assertProblem(ProblemType.INSUFFICIENT_ROLE);
    }

    @Test
    @Verifies("REQ-FUNC-022")
    void theMinimumRoleAndHigherOnesPassAndExposeTheAccess() throws Exception {
        for (Role role : List.of(Role.MEMBER, Role.ADMIN, Role.OWNER)) {
            lookup.result = Optional.of(new WorkspaceAccess(WORKSPACE, role));
            MockHttpServletRequest request = request(WORKSPACE.toString());

            assertThat(interceptor.preHandle(request, response(), handler("member")))
                    .as("role %s", role)
                    .isTrue();
            assertThat(request.getAttribute(WorkspaceAccess.REQUEST_ATTRIBUTE))
                    .isEqualTo(new WorkspaceAccess(WORKSPACE, role));
        }
    }

    @Test
    @Verifies("REQ-SEC-011")
    void aLookupFailureGets503WithRetryAfter() {
        lookup.failure = new MembershipUnavailableException("database down");

        assertThatThrownBy(() -> interceptor.preHandle(request(WORKSPACE.toString()), response(), handler("member")))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.type()).isEqualTo(ProblemType.SERVICE_UNAVAILABLE);
                    assertThat(problem.retryAfterSeconds()).hasValue(5L);
                });
    }

    @Test
    @Verifies("REQ-SEC-011")
    void withoutALookupImplementationRequestsGet503() {
        WorkspaceAuthorizationInterceptor withoutImplementation =
                new WorkspaceAuthorizationInterceptor(new WorkspaceAuthorizationConfiguration.DeferredMembershipLookup(
                        new DefaultListableBeanFactory().getBeanProvider(MembershipLookup.class)));

        assertThatThrownBy(() ->
                        withoutImplementation.preHandle(request(WORKSPACE.toString()), response(), handler("member")))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        problem -> assertThat(problem.type()).isEqualTo(ProblemType.SERVICE_UNAVAILABLE));
    }

    @Test
    @Verifies("REQ-SEC-011")
    void aMalformedWorkspaceIdGets404WithoutALookup() {
        assertThatThrownBy(() -> interceptor.preHandle(request("not-a-uuid"), response(), handler("member")))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        problem -> assertThat(problem.type()).isEqualTo(ProblemType.NOT_FOUND));
        assertThat(lookup.calls).isZero();
    }

    @Test
    void anAnnotatedHandlerWithoutWorkspaceIdIsAConfigurationError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of());

        assertThatThrownBy(() -> interceptor.preHandle(request, response(), handler("member")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{workspaceId}");
        assertThat(lookup.calls).isZero();
    }

    @Test
    @Verifies("REQ-SEC-011")
    void aRequestWithoutAnAuthenticatedUserGets401() {
        SecurityContextHolder.clearContext();

        assertProblem(ProblemType.UNAUTHENTICATED);
        assertThat(lookup.calls).isZero();
    }

    private void assertProblem(ProblemType expected) {
        assertThatThrownBy(() -> interceptor.preHandle(request(WORKSPACE.toString()), response(), handler("member")))
                .isInstanceOfSatisfying(
                        ProblemException.class,
                        problem -> assertThat(problem.type()).isEqualTo(expected));
    }

    private static MockHttpServletRequest request(String workspaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of(WorkspaceAuthorizationInterceptor.WORKSPACE_ID_VARIABLE, workspaceId));
        return request;
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getDeclaredMethod(name));
    }

    /** Stand-in handler methods. */
    static class Handlers {

        void open() {}

        @RequiresWorkspaceRole(Role.MEMBER)
        void member() {}
    }

    /** Returns a configured result or failure and records its calls. */
    static class FakeMembershipLookup implements MembershipLookup {

        Optional<WorkspaceAccess> result = Optional.empty();
        RuntimeException failure;
        int calls;
        UUID lastUserId;

        @Override
        public Optional<WorkspaceAccess> find(UUID workspaceId, UUID userId) {
            calls++;
            lastUserId = userId;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public List<UUID> workspaceIdsOf(UUID userId) {
            return List.of();
        }
    }
}
