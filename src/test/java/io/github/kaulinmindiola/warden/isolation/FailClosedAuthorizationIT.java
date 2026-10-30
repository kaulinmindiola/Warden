package io.github.kaulinmindiola.warden.isolation;

import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.github.kaulinmindiola.warden.workspace.infrastructure.WorkspaceMemberRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Fail-closed authorization under injected faults (REQ-SEC-011, SDD V12): for every registered
 * workspace endpoint, a workspace that cannot be resolved, an unknown stored role and a membership
 * lookup that fails are each injected, and no request succeeds.
 *
 * <p>The faults are injected below the real {@code MembershipLookup}: a primary {@link
 * WorkspaceMemberRepository} delegates to the real repository and, when told to, makes the
 * membership query fail or return a role the code does not know. The database cannot hold such a
 * role (a CHECK constraint forbids it), so the injector stands in for a row written by a newer
 * version of the schema. The caller is the OWNER of the workspace, who succeeds on every endpoint
 * when no fault is injected, so the fault is the only reason for each denial.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class FailClosedAuthorizationIT extends IntegrationTest {

    private static final String UNKNOWN_ROLE = "SUPERUSER";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MembershipFault fault;

    private WorkspaceFixture fixture;

    static List<WorkspaceEndpoint> endpoints() {
        return IsolationRegistry.ENDPOINTS;
    }

    @BeforeAll
    void createFixture() throws Exception {
        clock.reset();
        fixture = WorkspaceFixture.create(new IamTestClient(context), JdbcClient.create(superuserDataSource()));
    }

    @AfterEach
    void removeFault() {
        fault.inject(MembershipFault.Mode.NONE);
    }

    @Test
    void withoutAFaultTheOwnerIsAuthorized() throws Exception {
        MvcResult result = fixture.perform(
                MockMvcRequestBuilders.get(IsolationRegistry.WORKSPACE_ROUTE, fixture.workspace()),
                fixture.ownerToken());

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @Verifies("REQ-SEC-011")
    void whenMembershipsCannotBeReadTheRequestIsDeniedWith503(WorkspaceEndpoint endpoint) throws Exception {
        fault.inject(MembershipFault.Mode.LOOKUP_FAILS);

        MvcResult result = asOwner(endpoint);

        assertThat(result.getResponse().getStatus()).as("%s", endpoint).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Retry-After"))
                .as("Retry-After")
                .isEqualTo("5");
        assertThat(problemType(result))
                .isEqualTo(ProblemType.SERVICE_UNAVAILABLE.uri().toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @Verifies("REQ-SEC-011")
    void anUnknownStoredRoleIsDeniedLikeANonMemberAndLogged(WorkspaceEndpoint endpoint, CapturedOutput output)
            throws Exception {
        MvcResult nonMember = asNonMember(endpoint);
        fault.inject(MembershipFault.Mode.UNKNOWN_ROLE);
        int logBefore = output.getOut().length();

        MvcResult result = asOwner(endpoint);

        assertThat(result.getResponse().getStatus()).as("%s", endpoint).isEqualTo(404);
        assertIdenticalResponses(nonMember, result);
        assertThat(output.getOut().substring(logBefore))
                .contains("unknown role")
                .contains(fixture.workspace().toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @Verifies("REQ-SEC-011")
    void aWorkspaceThatCannotBeResolvedIsDeniedLikeANonMember(WorkspaceEndpoint endpoint) throws Exception {
        MvcResult nonMember = asNonMember(endpoint);

        MvcResult result = fixture.perform(withMalformedWorkspaceId(endpoint), fixture.ownerToken());

        assertThat(result.getResponse().getStatus()).as("%s", endpoint).isEqualTo(404);
        assertIdenticalResponses(nonMember, result);
    }

    private MvcResult asOwner(WorkspaceEndpoint endpoint) throws Exception {
        return fixture.perform(endpoint.requestFor(fixture.target(fixture.workspace())), fixture.ownerToken());
    }

    /** The response of a user who belongs to no workspace: the reference 404 (REQ-FUNC-023). */
    private MvcResult asNonMember(WorkspaceEndpoint endpoint) throws Exception {
        return fixture.perform(endpoint.requestFor(fixture.target(fixture.workspace())), fixture.outsiderToken());
    }

    /**
     * The endpoint with {@code not-a-uuid} as workspace id and random ids elsewhere. Writes carry a
     * JSON body so that the request reaches the authorization interceptor.
     */
    private static MockHttpServletRequestBuilder withMalformedWorkspaceId(WorkspaceEndpoint endpoint) {
        String path = endpoint.pattern()
                .replace("{workspaceId}", "not-a-uuid")
                .replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(endpoint.method(), path);
        if (endpoint.method() == HttpMethod.POST || endpoint.method() == HttpMethod.PUT) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return request;
    }

    private static String problemType(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.type");
    }

    /** The fault the injector applies to the membership query; none by default. */
    static final class MembershipFault {

        enum Mode {
            NONE,
            LOOKUP_FAILS,
            UNKNOWN_ROLE
        }

        private volatile Mode mode = Mode.NONE;

        void inject(Mode mode) {
            this.mode = mode;
        }

        Mode mode() {
            return mode;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultInjection {

        @Bean
        MembershipFault membershipFault() {
            return new MembershipFault();
        }

        /**
         * The real repository, except for the membership query {@code findActiveRole} while a
         * fault is injected. Every other call, and that one without a fault, goes to the real
         * repository.
         */
        @Bean
        @Primary
        WorkspaceMemberRepository faultInjectingWorkspaceMemberRepository(
                @Qualifier("workspaceMemberRepository") WorkspaceMemberRepository real, MembershipFault fault) {
            return (WorkspaceMemberRepository) Proxy.newProxyInstance(
                    WorkspaceMemberRepository.class.getClassLoader(),
                    new Class<?>[] {WorkspaceMemberRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("findActiveRole")) {
                            switch (fault.mode()) {
                                case LOOKUP_FAILS ->
                                    throw new DataAccessResourceFailureException(
                                            "Injected fault: memberships cannot be read");
                                case UNKNOWN_ROLE -> {
                                    return Optional.of(UNKNOWN_ROLE);
                                }
                                case NONE -> {
                                    // no fault: the real query runs
                                }
                            }
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
