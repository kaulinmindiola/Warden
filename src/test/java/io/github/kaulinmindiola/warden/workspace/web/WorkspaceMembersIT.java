package io.github.kaulinmindiola.warden.workspace.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * UC-15 and reading one workspace through the full filter chain: the first endpoints protected by
 * the workspace authorization interceptor and the real membership lookup.
 */
class WorkspaceMembersIT extends IntegrationTest {

    private static final String WORKSPACES = API + "/workspaces";
    private static final String NOT_FOUND = "urn:warden:problem:not-found";

    @Autowired
    private WebApplicationContext context;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies({"REQ-FUNC-037", "REQ-FUNC-023"})
    void aViewerSeesEveryMemberOfTheirWorkspaceAndNoneOfAnother() throws Exception {
        String ownerEmail = iam.registerVerifiedAccount();
        String ownerToken = iam.login(ownerEmail);
        UUID mine = createWorkspace(ownerToken, "Mine");
        UUID other = createWorkspace(ownerToken, "Other");
        String viewerEmail = iam.registerNewAccount();
        String outsiderEmail = iam.registerNewAccount();
        clock.advance(Duration.ofMinutes(1));
        addMember(mine, iam.userIdOf(viewerEmail), "VIEWER");
        addMember(other, iam.userIdOf(outsiderEmail), "MEMBER");

        String body = iam.get(WORKSPACES + "/" + mine + "/members", iam.login(viewerEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Map<String, Object>> items = JsonPath.read(body, "$.items");
        assertThat(items)
                .extracting(item -> item.get("userId"), item -> item.get("email"), item -> item.get("role"))
                .containsExactly(
                        tuple(iam.userIdOf(ownerEmail).toString(), ownerEmail, "OWNER"),
                        tuple(iam.userIdOf(viewerEmail).toString(), viewerEmail, "VIEWER"));
        assertThat(body).doesNotContain(outsiderEmail);
    }

    @Test
    @Verifies({"REQ-FUNC-023", "REQ-SEC-011"})
    void aNonMemberGetsTheSame404ForAnExistingWorkspaceAndARandomId() throws Exception {
        UUID existing = createWorkspace(iam.login(iam.registerVerifiedAccount()), "Private");
        String outsiderToken = iam.login(iam.registerNewAccount());

        MvcResult onExisting = iam.get(WORKSPACES + "/" + existing + "/members", outsiderToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND))
                .andReturn();
        MvcResult onRandom = iam.get(WORKSPACES + "/" + UUID.randomUUID() + "/members", outsiderToken)
                .andReturn();
        MvcResult onMalformed =
                iam.get(WORKSPACES + "/not-a-uuid/members", outsiderToken).andReturn();
        MvcResult workspaceItself =
                iam.get(WORKSPACES + "/" + existing, outsiderToken).andReturn();

        assertIdenticalResponses(onExisting, onRandom);
        assertIdenticalResponses(onExisting, onMalformed);
        assertIdenticalResponses(onExisting, workspaceItself);
    }

    @Test
    @Verifies({"REQ-FUNC-022", "BR-004"})
    void aRemovedMemberIsRejectedOnTheVeryNextRequest() throws Exception {
        UUID workspaceId = createWorkspace(iam.login(iam.registerVerifiedAccount()), "Team");
        String memberEmail = iam.registerNewAccount();
        UUID memberId = iam.userIdOf(memberEmail);
        addMember(workspaceId, memberId, "MEMBER");
        String memberToken = iam.login(memberEmail);
        iam.get(WORKSPACES + "/" + workspaceId + "/members", memberToken).andExpect(status().isOk());

        superuser()
                .sql("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?")
                .param(workspaceId)
                .param(memberId)
                .update();

        iam.get(WORKSPACES + "/" + workspaceId + "/members", memberToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND));
    }

    @Test
    @Verifies({"BR-012", "REQ-FUNC-023"})
    void aDeletedWorkspaceIsNotFoundEvenForItsOwner() throws Exception {
        String ownerToken = iam.login(iam.registerVerifiedAccount());
        UUID workspaceId = createWorkspace(ownerToken, "Soon gone");
        superuser()
                .sql("UPDATE workspace SET deleted_at = now() WHERE id = ?")
                .param(workspaceId)
                .update();

        iam.get(WORKSPACES + "/" + workspaceId + "/members", ownerToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND));
        iam.get(WORKSPACES + "/" + workspaceId, ownerToken).andExpect(status().isNotFound());
    }

    @Test
    void theLocationOfACreatedWorkspaceReturnsItWithTheCallersRole() throws Exception {
        String ownerToken = iam.login(iam.registerVerifiedAccount());
        MvcResult created = iam.postJson(WORKSPACES, Map.of("name", "Located"), ownerToken)
                .andExpect(status().isCreated())
                .andReturn();
        String location =
                URI.create(created.getResponse().getHeader("Location")).getPath();
        UUID workspaceId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        String viewerEmail = iam.registerNewAccount();
        addMember(workspaceId, iam.userIdOf(viewerEmail), "VIEWER");

        iam.get(location, ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workspaceId.toString()))
                .andExpect(jsonPath("$.name").value("Located"))
                .andExpect(jsonPath("$.role").value("OWNER"));
        iam.get(location, iam.login(viewerEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    private UUID createWorkspace(String accessToken, String name) throws Exception {
        String body = iam.postJson(WORKSPACES, Map.of("name", name), accessToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Seeds a membership directly; the endpoints that add members arrive in later steps. */
    private static void addMember(UUID workspaceId, UUID userId, String role) {
        superuser()
                .sql("INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, ?, now())")
                .param(workspaceId)
                .param(userId)
                .param(role)
                .update();
    }

    /** Only for seeding: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
