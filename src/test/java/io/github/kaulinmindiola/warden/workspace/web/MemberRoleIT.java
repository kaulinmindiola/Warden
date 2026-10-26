package io.github.kaulinmindiola.warden.workspace.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** UC-16 through the full filter chain: authority rules, the last OWNER, the event and BR-004. */
class MemberRoleIT extends IntegrationTest {

    private static final String INSUFFICIENT_ROLE = "urn:warden:problem:insufficient-role";
    private static final String EVENT = "workspace.MemberRoleChanged";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    private IamTestClient iam;
    private String ownerEmail;
    private UUID ownerId;
    private String ownerToken;
    private UUID workspaceId;

    @BeforeEach
    void createWorkspace() throws Exception {
        iam = new IamTestClient(context);
        ownerEmail = iam.registerVerifiedAccount();
        ownerId = iam.userIdOf(ownerEmail);
        ownerToken = iam.login(ownerEmail);
        workspaceId = createWorkspace("Team");
    }

    @Test
    @Verifies({"REQ-FUNC-038", "BR-008"})
    void anOwnerPromotesAMemberToOwner() throws Exception {
        Member member = member(workspaceId, "MEMBER");

        changeRole(ownerToken, workspaceId, member.userId(), "OWNER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(member.userId().toString()))
                .andExpect(jsonPath("$.email").value(member.email()))
                .andExpect(jsonPath("$.role").value("OWNER"));

        assertThat(storedRole(workspaceId, member.userId())).isEqualTo("OWNER");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'MEMBER_ROLE_CHANGED' AND workspace_id = ?"))
                .isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-FUNC-038", "BR-002", "BR-008"})
    void anAdminCannotAssignOwnerNorChangeAnOwner() throws Exception {
        Member admin = member(workspaceId, "ADMIN");
        Member member = member(workspaceId, "MEMBER");

        changeRole(admin.token(), workspaceId, member.userId(), "OWNER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(INSUFFICIENT_ROLE));
        changeRole(admin.token(), workspaceId, ownerId, "VIEWER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(INSUFFICIENT_ROLE));

        assertThat(storedRole(workspaceId, member.userId())).isEqualTo("MEMBER");
        assertThat(storedRole(workspaceId, ownerId)).isEqualTo("OWNER");
        assertThat(eventCount()).isZero();
    }

    @Test
    @Verifies("REQ-FUNC-038")
    void anAdminDemotesAnotherAdminToViewer() throws Exception {
        Member admin = member(workspaceId, "ADMIN");
        Member otherAdmin = member(workspaceId, "ADMIN");

        changeRole(admin.token(), workspaceId, otherAdmin.userId(), "VIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        assertThat(storedRole(workspaceId, otherAdmin.userId())).isEqualTo("VIEWER");
    }

    @Test
    @Verifies({"REQ-FUNC-022", "BR-004"})
    void aDemotedAdminIsRejectedOnTheNextRequestWithTheSameToken() throws Exception {
        Member admin = member(workspaceId, "ADMIN");
        Member member = member(workspaceId, "MEMBER");
        changeRole(admin.token(), workspaceId, member.userId(), "VIEWER").andExpect(status().isOk());

        changeRole(ownerToken, workspaceId, admin.userId(), "MEMBER").andExpect(status().isOk());

        changeRole(admin.token(), workspaceId, member.userId(), "MEMBER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(INSUFFICIENT_ROLE));
        assertThat(storedRole(workspaceId, member.userId())).isEqualTo("VIEWER");
    }

    @Test
    @Verifies({"REQ-FUNC-039", "BR-001", "BR-011"})
    void theLastOwnerCannotBeDemotedButOneOfTwoOwnersCan() throws Exception {
        changeRole(ownerToken, workspaceId, ownerId, "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:last-owner"));
        assertThat(storedRole(workspaceId, ownerId)).isEqualTo("OWNER");

        Member secondOwner = member(workspaceId, "OWNER");
        changeRole(ownerToken, workspaceId, secondOwner.userId(), "ADMIN").andExpect(status().isOk());

        changeRole(ownerToken, workspaceId, ownerId, "ADMIN").andExpect(status().isConflict());
        assertThat(storedRole(workspaceId, ownerId)).isEqualTo("OWNER");
    }

    @Test
    @Verifies("REQ-FUNC-040")
    void theEventNamesTheAffectedMemberAndEveryOwner() throws Exception {
        Member secondOwner = member(workspaceId, "OWNER");
        Member member = member(workspaceId, "MEMBER");

        changeRole(ownerToken, workspaceId, member.userId(), "ADMIN").andExpect(status().isOk());

        assertThat(eventCount()).isEqualTo(1);
        String payload = JdbcClient.create(superuserDataSource())
                .sql("SELECT payload::text FROM outbox_event WHERE type = ? AND workspace_id = ?")
                .param(EVENT)
                .param(workspaceId)
                .query(String.class)
                .single();
        assertThat((String) JsonPath.read(payload, "$.workspaceName")).isEqualTo("Team");
        assertThat((String) JsonPath.read(payload, "$.affected.email")).isEqualTo(member.email());
        assertThat((String) JsonPath.read(payload, "$.affected.userId"))
                .isEqualTo(member.userId().toString());
        assertThat((String) JsonPath.read(payload, "$.affected.fallbackLocale")).isEqualTo("es");
        List<String> ownerEmails = JsonPath.read(payload, "$.owners[*].email");
        assertThat(ownerEmails).containsExactlyInAnyOrder(ownerEmail, secondOwner.email());
        assertThat((String) JsonPath.read(payload, "$.previousRole")).isEqualTo("MEMBER");
        assertThat((String) JsonPath.read(payload, "$.newRole")).isEqualTo("ADMIN");
        assertThat((String) JsonPath.read(payload, "$.changedBy")).isEqualTo(ownerId.toString());
    }

    @Test
    @Verifies({"REQ-FUNC-025", "REQ-FUNC-038"})
    void unknownTargetsMembersOfAnotherWorkspaceAndInvalidRolesAreRejected() throws Exception {
        Member member = member(workspaceId, "MEMBER");
        UUID other = createWorkspace("Other");
        Member memberOfOther = member(other, "MEMBER");

        changeRole(ownerToken, workspaceId, UUID.randomUUID(), "VIEWER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:not-found"));
        changeRole(ownerToken, workspaceId, memberOfOther.userId(), "VIEWER").andExpect(status().isNotFound());
        assertThat(storedRole(other, memberOfOther.userId())).isEqualTo("MEMBER");

        changeRole(ownerToken, workspaceId, member.userId(), "SUPERUSER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("role"))
                .andExpect(jsonPath("$.errors[0].reason").value("must be one of: VIEWER, MEMBER, ADMIN, OWNER"));
        changeRole(ownerToken, workspaceId, member.userId(), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].reason").value("is required"));

        changeRole(ownerToken, workspaceId, member.userId(), "MEMBER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));
        assertThat(eventCount()).isZero();
    }

    private record Member(UUID userId, String email, String token) {}

    /** A new user with the given role in a workspace, seeded as the superuser. */
    private Member member(UUID workspace, String role) throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        JdbcClient.create(superuserDataSource())
                .sql("INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, ?, now())")
                .param(workspace)
                .param(userId)
                .param(role)
                .update();
        return new Member(userId, email, iam.login(email));
    }

    private UUID createWorkspace(String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions changeRole(String token, UUID workspace, UUID userId, String role) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("role", role);
        return iam.mvc()
                .perform(MockMvcRequestBuilders.put(API + "/workspaces/{w}/members/{u}/role", workspace, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String storedRole(UUID workspace, UUID userId) {
        return JdbcClient.create(superuserDataSource())
                .sql("SELECT role FROM workspace_member WHERE workspace_id = ? AND user_id = ?")
                .param(workspace)
                .param(userId)
                .query(String.class)
                .single();
    }

    private long eventCount() {
        return count("SELECT count(*) FROM outbox_event WHERE type = '" + EVENT + "' AND workspace_id = ?");
    }

    private long count(String sql) {
        return JdbcClient.create(superuserDataSource())
                .sql(sql)
                .param(workspaceId)
                .query(Long.class)
                .single();
    }
}
