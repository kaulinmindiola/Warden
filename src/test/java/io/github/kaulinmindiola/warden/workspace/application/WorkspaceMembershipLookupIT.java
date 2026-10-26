package io.github.kaulinmindiola.warden.workspace.application;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.security.MembershipLookup;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.security.WorkspaceAccess;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/** The real lookup against PostgreSQL with Row Level Security (SDD V09). */
class WorkspaceMembershipLookupIT extends IntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MembershipLookup membershipLookup;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies("REQ-FUNC-022")
    void aMemberGetsTheirRoleAndANonMemberNothing() throws Exception {
        String ownerEmail = iam.registerVerifiedAccount();
        UUID ownerId = iam.userIdOf(ownerEmail);
        UUID workspaceId = createWorkspace(iam.login(ownerEmail), "Lookup");

        assertThat(membershipLookup.find(workspaceId, ownerId)).contains(new WorkspaceAccess(workspaceId, Role.OWNER));
        assertThat(membershipLookup.find(workspaceId, UUID.randomUUID())).isEmpty();
        assertThat(membershipLookup.find(UUID.randomUUID(), ownerId)).isEmpty();
    }

    @Test
    @Verifies("BR-012")
    void aDeletedWorkspaceGrantsNoAccess() throws Exception {
        String ownerEmail = iam.registerVerifiedAccount();
        UUID ownerId = iam.userIdOf(ownerEmail);
        UUID workspaceId = createWorkspace(iam.login(ownerEmail), "Gone");

        superuser()
                .sql("UPDATE workspace SET deleted_at = now() WHERE id = ?")
                .param(workspaceId)
                .update();

        assertThat(membershipLookup.find(workspaceId, ownerId)).isEmpty();
    }

    @Test
    void theWorkspacesOfAUserExcludeDeletedOnesAndThoseOfOthers() throws Exception {
        String ownerEmail = iam.registerVerifiedAccount();
        UUID ownerId = iam.userIdOf(ownerEmail);
        String accessToken = iam.login(ownerEmail);
        UUID first = createWorkspace(accessToken, "First");
        clock.advance(Duration.ofSeconds(1));
        UUID second = createWorkspace(accessToken, "Second");
        clock.advance(Duration.ofSeconds(1));
        UUID deleted = createWorkspace(accessToken, "Deleted");
        createWorkspace(iam.login(iam.registerVerifiedAccount()), "Someone else");
        superuser()
                .sql("UPDATE workspace SET deleted_at = now() WHERE id = ?")
                .param(deleted)
                .update();

        assertThat(membershipLookup.workspaceIdsOf(ownerId)).containsExactly(first, second);
    }

    private UUID createWorkspace(String accessToken, String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), accessToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Only for seeding: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
