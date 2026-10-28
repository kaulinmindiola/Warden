package io.github.kaulinmindiola.warden.isolation;

import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * Isolation suite (SDD V07, REQ-FUNC-023, REQ-FUNC-026, REQ-MAINT-003): for every registered
 * endpoint, a non-member, the OWNER of another workspace and, once the workspace is deleted, even
 * its former OWNER get exactly the response of a random workspace id, a 404 that reveals nothing.
 */
class IsolationSuiteIT extends IntegrationTest {

    private static final String NOT_FOUND = "urn:warden:problem:not-found";

    @Autowired
    private WebApplicationContext context;

    static List<WorkspaceEndpoint> endpoints() {
        return IsolationRegistry.ENDPOINTS;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @Verifies({"REQ-FUNC-023", "REQ-MAINT-003"})
    void foreignWorkspacesAreIndistinguishableFromNonexistentOnes(WorkspaceEndpoint endpoint) throws Exception {
        WorkspaceFixture fixture = newFixture();

        MvcResult randomId =
                fixture.perform(endpoint.requestFor(fixture.target(UUID.randomUUID())), fixture.outsiderToken());
        MvcResult nonMember =
                fixture.perform(endpoint.requestFor(fixture.target(fixture.workspace())), fixture.outsiderToken());
        MvcResult memberOfOther =
                fixture.perform(endpoint.requestFor(fixture.target(fixture.workspace())), fixture.otherMemberToken());

        assertNotFound(randomId);
        assertIdenticalResponses(randomId, nonMember);
        assertIdenticalResponses(randomId, memberOfOther);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @Verifies({"REQ-FUNC-026", "BR-012", "REQ-MAINT-003"})
    void aDeletedWorkspaceIsIndistinguishableFromANonexistentOneEvenForItsFormerMembers(WorkspaceEndpoint endpoint)
            throws Exception {
        WorkspaceFixture fixture = newFixture();
        String formerMember = fixture.tokenWithRole(endpoint.minimumRole());
        MvcResult randomId =
                fixture.perform(endpoint.requestFor(fixture.target(UUID.randomUUID())), fixture.ownerToken());

        fixture.deleteWorkspace();

        MvcResult formerOwner =
                fixture.perform(endpoint.requestFor(fixture.target(fixture.workspace())), fixture.ownerToken());
        MvcResult formerMemberWithTheMinimumRole =
                fixture.perform(endpoint.requestFor(fixture.target(fixture.workspace())), formerMember);

        assertNotFound(randomId);
        assertIdenticalResponses(randomId, formerOwner);
        assertIdenticalResponses(randomId, formerMemberWithTheMinimumRole);
    }

    private WorkspaceFixture newFixture() throws Exception {
        return WorkspaceFixture.create(new IamTestClient(context), JdbcClient.create(superuserDataSource()));
    }

    private static void assertNotFound(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("random workspace id").isEqualTo(404);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.type"))
                .isEqualTo(NOT_FOUND);
    }
}
