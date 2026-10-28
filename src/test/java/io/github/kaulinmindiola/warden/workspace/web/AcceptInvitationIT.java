package io.github.kaulinmindiola.warden.workspace.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/** UC-14 through the full filter chain (REQ-FUNC-036, BR-009). */
class AcceptInvitationIT extends IntegrationTest {

    private static final String ACCEPT = API + "/invitations/accept";
    private static final String INVALID_TOKEN = "urn:warden:problem:invalid-one-time-token";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretCipher secretCipher;

    private IamTestClient iam;
    private String ownerEmail;
    private String ownerToken;
    private UUID workspaceId;

    @BeforeEach
    void createWorkspace() throws Exception {
        iam = new IamTestClient(context);
        ownerEmail = iam.registerVerifiedAccount();
        ownerToken = iam.login(ownerEmail);
        workspaceId = createWorkspace("Team");
    }

    @Test
    @Verifies({"REQ-FUNC-036", "BR-009"})
    void aVerifiedAccountWithTheInvitedEmailJoinsWithTheInvitedRole() throws Exception {
        String email = iam.registerVerifiedAccount();
        UUID userId = iam.userIdOf(email);
        UUID invitationId = invite(workspaceId, email.toUpperCase(Locale.ROOT), "MEMBER");
        String token = tokenOf(invitationId);
        String accessToken = iam.login(email);

        accept(token, accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        assertThat(roleOf(workspaceId, userId)).contains("MEMBER");
        assertThat(statusOf(invitationId)).isEqualTo("ACCEPTED");
        assertThat(acceptedEvents(invitationId)).isEqualTo(1);
        assertThat(acceptanceAudits("SUCCESS")).isEqualTo(1);
        iam.get(API + "/workspaces/" + workspaceId, accessToken).andExpect(status().isOk());

        accept(token, accessToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(INVALID_TOKEN));
        assertThat(roleOf(workspaceId, userId)).contains("MEMBER");
        assertThat(acceptedEvents(invitationId)).isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-FUNC-036", "BR-009"})
    void anotherOrAnUnverifiedAccountGets403AndTheTokenStaysUsable() throws Exception {
        String invited = iam.registerVerifiedAccount();
        String intruder = iam.registerVerifiedAccount();
        UUID invitationId = invite(workspaceId, invited, "VIEWER");
        String token = tokenOf(invitationId);

        accept(token, iam.login(intruder))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:invitation-email-mismatch"));
        assertThat(statusOf(invitationId)).isEqualTo("PENDING");
        assertThat(roleOf(workspaceId, iam.userIdOf(intruder))).isEmpty();
        assertThat(acceptanceAudits("FAILURE")).isEqualTo(1);

        String unverified = iam.registerNewAccount();
        UUID unverifiedInvitation = invite(workspaceId, unverified, "MEMBER");
        accept(tokenOf(unverifiedInvitation), iam.login(unverified))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:email-not-verified"));
        assertThat(statusOf(unverifiedInvitation)).isEqualTo("PENDING");
        assertThat(roleOf(workspaceId, iam.userIdOf(unverified))).isEmpty();
        assertThat(acceptanceAudits("FAILURE")).isEqualTo(2);

        accept(token, iam.login(invited))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
        assertThat(statusOf(invitationId)).isEqualTo("ACCEPTED");
    }

    @Test
    @Verifies({"REQ-FUNC-036", "REQ-FUNC-032", "REQ-FUNC-035", "BR-012"})
    void unknownUsedExpiredRevokedReplacedAndDeletedWorkspaceTokensGetTheSameResponse() throws Exception {
        String email = iam.registerVerifiedAccount();
        String accessToken = iam.login(email);
        String replaced = tokenOf(invite(workspaceId, email, "MEMBER"));
        UUID revokedId = invite(workspaceId, email, "VIEWER");
        String revoked = tokenOf(revokedId);
        iam.delete(API + "/workspaces/" + workspaceId + "/invitations/" + revokedId, ownerToken)
                .andExpect(status().isNoContent());
        UUID gone = createWorkspace("Gone");
        String ofDeletedWorkspace = tokenOf(invite(gone, email, "MEMBER"));
        superuser()
                .sql("UPDATE workspace SET deleted_at = now() WHERE id = ?")
                .param(gone)
                .update();
        String expired = tokenOf(invite(workspaceId, email, "MEMBER"));

        MvcResult unknown = accept(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX + "unknown", accessToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(INVALID_TOKEN))
                .andReturn();
        List<MvcResult> rejected = new ArrayList<>();
        rejected.add(accept("not-a-token", accessToken).andReturn());
        rejected.add(accept(null, accessToken).andReturn());
        rejected.add(accept(replaced, accessToken).andReturn());
        rejected.add(accept(revoked, accessToken).andReturn());
        rejected.add(accept(ofDeletedWorkspace, accessToken).andReturn());

        clock.advance(Duration.ofDays(7).plusSeconds(1));
        ownerToken = iam.login(ownerEmail);
        accessToken = iam.login(email);
        rejected.add(accept(expired, accessToken).andReturn());
        String used = tokenOf(invite(workspaceId, email, "MEMBER"));
        accept(used, accessToken).andExpect(status().isOk());
        rejected.add(accept(used, accessToken).andReturn());

        for (MvcResult response : rejected) {
            assertIdenticalResponses(unknown, response);
        }
        assertThat(roleOf(workspaceId, iam.userIdOf(email))).contains("MEMBER");
        assertThat(roleOf(gone, iam.userIdOf(email))).isEmpty();
    }

    @Test
    @Verifies("REQ-FUNC-036")
    void aMemberAcceptingGets409AndKeepsTheirRole() throws Exception {
        String email = iam.registerVerifiedAccount();
        UUID userId = iam.userIdOf(email);
        UUID invitationId = invite(workspaceId, email, "ADMIN");
        superuser()
                .sql(
                        "INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, 'VIEWER', now())")
                .param(workspaceId)
                .param(userId)
                .update();

        accept(tokenOf(invitationId), iam.login(email))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:already-member"));

        assertThat(roleOf(workspaceId, userId)).contains("VIEWER");
        assertThat(statusOf(invitationId)).isEqualTo("PENDING");
    }

    private ResultActions accept(String token, String accessToken) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        return iam.postJson(ACCEPT, body, accessToken);
    }

    private UUID invite(UUID workspace, String email, String role) throws Exception {
        String body = iam.postJson(
                        API + "/workspaces/" + workspace + "/invitations",
                        Map.of("email", email, "role", role),
                        ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID createWorkspace(String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
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

    private Optional<String> roleOf(UUID workspace, UUID userId) {
        return superuser()
                .sql("SELECT role FROM workspace_member WHERE workspace_id = ? AND user_id = ?")
                .param(workspace)
                .param(userId)
                .query(String.class)
                .optional();
    }

    private String statusOf(UUID invitationId) {
        return superuser()
                .sql("SELECT status FROM invitation WHERE id = ?")
                .param(invitationId)
                .query(String.class)
                .single();
    }

    private long acceptedEvents(UUID invitationId) {
        return superuser()
                .sql(
                        "SELECT count(*) FROM outbox_event WHERE type = 'workspace.InvitationAccepted' AND payload ->> 'invitationId' = ?")
                .param(invitationId.toString())
                .query(Long.class)
                .single();
    }

    private long acceptanceAudits(String outcome) {
        return superuser()
                .sql(
                        "SELECT count(*) FROM audit_log WHERE action = 'INVITATION_ACCEPTED' AND workspace_id = ? AND outcome = ?")
                .param(workspaceId)
                .param(outcome)
                .query(Long.class)
                .single();
    }

    /** Only for seeding and inspection: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
