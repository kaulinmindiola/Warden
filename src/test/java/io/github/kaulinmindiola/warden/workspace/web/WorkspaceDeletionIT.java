package io.github.kaulinmindiola.warden.workspace.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/** UC-19 through the full filter chain (REQ-FUNC-026, 029, 030; BR-012). */
class WorkspaceDeletionIT extends IntegrationTest {

    private static final String NOT_FOUND = "urn:warden:problem:not-found";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretCipher secretCipher;

    private IamTestClient iam;
    private String ownerToken;
    private UUID workspaceId;
    private UUID otherWorkspaceId;

    @BeforeEach
    void createTwoWorkspaces() throws Exception {
        iam = new IamTestClient(context);
        ownerToken = iam.login(iam.registerVerifiedAccount());
        workspaceId = createWorkspace("Team");
        otherWorkspaceId = createWorkspace("Other");
    }

    @Test
    @Verifies({"REQ-FUNC-029", "REQ-FUNC-026", "REQ-FUNC-030", "BR-012"})
    void anOwnerDeletesTheWorkspaceAndItBehavesAsNonexistentForEveryone() throws Exception {
        String memberEmail = iam.registerVerifiedAccount();
        String memberToken = iam.login(memberEmail);
        UUID acceptedId = invite(workspaceId, memberEmail);
        accept(tokenOf(acceptedId), memberToken).andExpect(status().isOk());
        String guestEmail = iam.registerVerifiedAccount();
        UUID pendingId = invite(workspaceId, guestEmail);
        String pendingToken = tokenOf(pendingId);
        UUID ofOther = invite(otherWorkspaceId, guestEmail);

        iam.delete(API + "/workspaces/" + workspaceId, ownerToken).andExpect(status().isNoContent());

        for (String path : List.of("", "/members", "/notes", "/invitations")) {
            iam.get(API + "/workspaces/" + workspaceId + path, ownerToken)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value(NOT_FOUND));
        }
        iam.get(API + "/workspaces/" + workspaceId, memberToken).andExpect(status().isNotFound());
        iam.delete(API + "/workspaces/" + workspaceId, ownerToken).andExpect(status().isNotFound());
        String listed = iam.get(API + "/workspaces", ownerToken)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> ids = JsonPath.read(listed, "$.items[*].id");
        assertThat(ids).containsExactly(otherWorkspaceId.toString());

        assertThat(statusOf(pendingId)).isEqualTo("REVOKED");
        assertThat(statusOf(acceptedId)).isEqualTo("ACCEPTED");
        assertThat(statusOf(ofOther)).isEqualTo("PENDING");
        accept(pendingToken, iam.login(guestEmail))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:invalid-one-time-token"));

        assertThat(superuser()
                        .sql("SELECT deleted_at IS NOT NULL FROM workspace WHERE id = ?")
                        .param(workspaceId)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(superuser()
                        .sql(
                                "SELECT payload::text FROM outbox_event WHERE type = 'workspace.WorkspaceDeleted' AND workspace_id = ?")
                        .param(workspaceId)
                        .query(String.class)
                        .list())
                .containsExactly("{}");
        assertThat(superuser().sql("""
                                SELECT details ->> 'revokedInvitations' FROM audit_log
                                WHERE action = 'WORKSPACE_DELETED' AND workspace_id = ? AND outcome = 'SUCCESS'
                                """).param(workspaceId).query(String.class).single())
                .isEqualTo("1");
    }

    @Test
    @Verifies({"REQ-FUNC-029", "BR-002"})
    void anAdminCannotDeleteTheWorkspace() throws Exception {
        String adminEmail = iam.registerVerifiedAccount();
        superuser()
                .sql(
                        "INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, 'ADMIN', now())")
                .param(workspaceId)
                .param(iam.userIdOf(adminEmail))
                .update();

        iam.delete(API + "/workspaces/" + workspaceId, iam.login(adminEmail))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:insufficient-role"));

        iam.get(API + "/workspaces/" + workspaceId, ownerToken).andExpect(status().isOk());
        assertThat(superuser()
                        .sql(
                                "SELECT count(*) FROM outbox_event WHERE type = 'workspace.WorkspaceDeleted' AND workspace_id = ?")
                        .param(workspaceId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private UUID createWorkspace(String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID invite(UUID workspace, String email) throws Exception {
        String body = iam.postJson(
                        API + "/workspaces/" + workspace + "/invitations",
                        Map.of("email", email, "role", "MEMBER"),
                        ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions accept(String token, String accessToken) throws Exception {
        return iam.postJson(API + "/invitations/accept", Map.of("token", token), accessToken);
    }

    /** Opens the sealed token of the invitation event, as the notification module will. */
    private String tokenOf(UUID invitationId) {
        Map<String, Object> secret =
                superuser().sql("""
                        SELECT payload -> 'secret' ->> 'ciphertext' AS ciphertext,
                               payload -> 'secret' ->> 'keyId' AS key_id,
                               payload -> 'secret' ->> 'expiresAt' AS expires_at
                        FROM outbox_event
                        WHERE type = 'workspace.MemberInvited' AND payload ->> 'invitationId' = ?
                        """).param(invitationId.toString()).query().singleRow();
        return secretCipher.open(new SealedSecret(
                (String) secret.get("ciphertext"), (String) secret.get("key_id"), Instant.parse((String)
                        secret.get("expires_at"))));
    }

    private String statusOf(UUID invitationId) {
        return superuser()
                .sql("SELECT status FROM invitation WHERE id = ?")
                .param(invitationId)
                .query(String.class)
                .single();
    }

    /** Only for seeding and inspection: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
