package io.github.kaulinmindiola.warden.workspace.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/** UC-17 and UC-18 through the full filter chain, with the last-OWNER guard. */
class MemberRemovalIT extends IntegrationTest {

    private static final String NOT_FOUND = "urn:warden:problem:not-found";
    private static final String LAST_OWNER = "urn:warden:problem:last-owner";

    @Autowired
    private WebApplicationContext context;

    private IamTestClient iam;
    private UUID ownerId;
    private String ownerToken;
    private UUID workspaceId;

    @BeforeEach
    void createWorkspace() throws Exception {
        iam = new IamTestClient(context);
        String ownerEmail = iam.registerVerifiedAccount();
        ownerId = iam.userIdOf(ownerEmail);
        ownerToken = iam.login(ownerEmail);
        workspaceId = createWorkspace("Team");
    }

    @Test
    @Verifies({"REQ-FUNC-041", "REQ-FUNC-022"})
    void anAdminRemovesAMemberWhoIsRejectedNextWhileTheirNotesStay() throws Exception {
        Member admin = member(workspaceId, "ADMIN");
        Member member = member(workspaceId, "MEMBER");
        String noteId = JsonPath.read(
                iam.postJson(notes(), Map.of("title", "Kept", "body", "Body"), member.token())
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");

        remove(admin.token(), member.userId()).andExpect(status().isNoContent());

        iam.get(members(), member.token())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND));
        iam.get(notes() + "/" + noteId, ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorId").value(member.userId().toString()));
        assertThat(isMember(member.userId())).isFalse();
        assertThat(auditCount("MEMBER_REMOVED")).isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-FUNC-041", "BR-002"})
    void anAdminCannotRemoveAnOwner() throws Exception {
        Member admin = member(workspaceId, "ADMIN");

        remove(admin.token(), ownerId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:insufficient-role"));

        assertThat(isMember(ownerId)).isTrue();
    }

    @Test
    @Verifies({"REQ-FUNC-039", "BR-001", "BR-011"})
    void theLastOwnerCannotBeRemovedOrLeaveButOneOfTwoCan() throws Exception {
        remove(ownerToken, ownerId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(LAST_OWNER));
        leave(ownerToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(LAST_OWNER));
        assertThat(isMember(ownerId)).isTrue();

        Member secondOwner = member(workspaceId, "OWNER");
        Member thirdOwner = member(workspaceId, "OWNER");
        remove(ownerToken, secondOwner.userId()).andExpect(status().isNoContent());
        leave(thirdOwner.token()).andExpect(status().isNoContent());

        assertThat(isMember(secondOwner.userId())).isFalse();
        assertThat(isMember(thirdOwner.userId())).isFalse();
        leave(ownerToken).andExpect(status().isConflict());
    }

    @Test
    @Verifies("REQ-FUNC-042")
    void aViewerLeavesAndTheWorkspaceDisappearsFromTheirList() throws Exception {
        Member viewer = member(workspaceId, "VIEWER");
        iam.get(API + "/workspaces", viewer.token())
                .andExpect(jsonPath("$.totalElements").value(1));

        leave(viewer.token()).andExpect(status().isNoContent());

        iam.get(API + "/workspaces", viewer.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        iam.get(API + "/workspaces/" + workspaceId, viewer.token()).andExpect(status().isNotFound());
        assertThat(auditCount("MEMBER_LEFT")).isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-FUNC-025", "REQ-FUNC-041"})
    void unknownTargetsAndMembersOfAnotherWorkspaceAre404() throws Exception {
        UUID other = createWorkspace("Other");
        Member memberOfOther = member(other, "MEMBER");

        remove(ownerToken, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND));
        remove(ownerToken, memberOfOther.userId()).andExpect(status().isNotFound());

        assertThat(JdbcClient.create(superuserDataSource())
                        .sql("SELECT count(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?")
                        .param(other)
                        .param(memberOfOther.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    private record Member(UUID userId, String token) {}

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
        return new Member(userId, iam.login(email));
    }

    private UUID createWorkspace(String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String members() {
        return API + "/workspaces/" + workspaceId + "/members";
    }

    private String notes() {
        return API + "/workspaces/" + workspaceId + "/notes";
    }

    private ResultActions remove(String token, UUID userId) throws Exception {
        return iam.delete(members() + "/" + userId, token);
    }

    private ResultActions leave(String token) throws Exception {
        return iam.postJson(API + "/workspaces/" + workspaceId + "/leave", Map.of(), token);
    }

    private boolean isMember(UUID userId) {
        return JdbcClient.create(superuserDataSource())
                        .sql("SELECT count(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?")
                        .param(workspaceId)
                        .param(userId)
                        .query(Long.class)
                        .single()
                == 1;
    }

    private long auditCount(String action) {
        return JdbcClient.create(superuserDataSource())
                .sql("SELECT count(*) FROM audit_log WHERE action = ? AND workspace_id = ?")
                .param(action)
                .param(workspaceId)
                .query(Long.class)
                .single();
    }
}
