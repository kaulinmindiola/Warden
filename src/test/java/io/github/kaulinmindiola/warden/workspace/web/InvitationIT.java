package io.github.kaulinmindiola.warden.workspace.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/** UC-12 and UC-13 through the full filter chain: creation, re-invitation, listing and revocation. */
class InvitationIT extends IntegrationTest {

    private static final String VALIDATION_ERROR = "urn:warden:problem:validation-error";
    private static final String NOT_PENDING = "urn:warden:problem:invitation-not-pending";
    private static final String INVITED = "workspace.MemberInvited";
    private static final String REVOKED = "workspace.InvitationRevoked";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private TokenHasher tokenHasher;

    private IamTestClient iam;
    private String ownerToken;
    private UUID workspaceId;
    private String adminEmail;
    private String adminToken;

    @BeforeEach
    void createWorkspaceWithAnAdmin() throws Exception {
        iam = new IamTestClient(context);
        ownerToken = iam.login(iam.registerVerifiedAccount());
        workspaceId = createWorkspace("Team");
        Member admin = member(workspaceId, "ADMIN");
        adminEmail = admin.email();
        adminToken = admin.token();
    }

    @Test
    @Verifies({"REQ-FUNC-031", "REQ-SEC-004", "REQ-SEC-005"})
    void anAdminInvitesAnAddressAndOnlyItsSealedTokenLeaves() throws Exception {
        String invited = "guest-" + UUID.randomUUID() + "@mail.com";
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        String body = invite(adminToken, "  " + invited.toUpperCase(Locale.ROOT) + " ", "MEMBER", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(invited))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String invitationId = JsonPath.read(body, "$.id");
        assertThat(Instant.parse(JsonPath.read(body, "$.expiresAt"))).isEqualTo(now.plus(Duration.ofDays(7)));
        assertThat(body)
                .doesNotContain(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)
                .doesNotContainIgnoringCase("token");

        String payload = eventPayload(INVITED, invitationId);
        Map<String, Object> recipient = JsonPath.read(payload, "$.recipient");
        assertThat(recipient.get("userId")).isNull();
        assertThat(recipient.get("email")).isEqualTo(invited);
        assertThat(recipient.get("fallbackLocale")).isEqualTo("es");
        assertThat((String) JsonPath.read(payload, "$.workspaceName")).isEqualTo("Team");
        assertThat((String) JsonPath.read(payload, "$.role")).isEqualTo("MEMBER");
        String token = secretCipher.open(new SealedSecret(
                JsonPath.read(payload, "$.secret.ciphertext"),
                JsonPath.read(payload, "$.secret.keyId"),
                Instant.parse(JsonPath.read(payload, "$.secret.expiresAt"))));
        assertThat(token).startsWith(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX);
        assertThat(superuser()
                        .sql("SELECT token_hash FROM invitation WHERE id = ?")
                        .param(UUID.fromString(invitationId))
                        .query(byte[].class)
                        .single())
                .isEqualTo(tokenHasher.sha256(token));
        assertThat(auditCount("INVITATION_CREATED")).isEqualTo(1);

        invite(adminToken, "other-" + UUID.randomUUID() + "@mail.com", "VIEWER", "en")
                .andExpect(status().isCreated());
    }

    @Test
    @Verifies({"REQ-FUNC-031", "BR-008"})
    void ownerAndInvalidFieldsAreRejected() throws Exception {
        invite(adminToken, "guest@mail.com", "OWNER", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.errors[0].field").value("role"))
                .andExpect(jsonPath("$.errors[0].reason").value("must be one of: ADMIN, MEMBER, VIEWER"));
        invite(adminToken, "not-an-email", "MEMBER", "fr")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[1].field").value("locale"));
        invite(adminToken, null, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].reason").value("is required"));

        assertThat(superuser()
                        .sql("SELECT count(*) FROM invitation WHERE workspace_id = ?")
                        .param(workspaceId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @Verifies("REQ-FUNC-031")
    void anAddressThatAlreadyBelongsToAMemberGets409() throws Exception {
        Member member = member(workspaceId, "VIEWER");

        invite(adminToken, member.email().toUpperCase(Locale.ROOT), "MEMBER", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:already-member"));
    }

    @Test
    @Verifies("REQ-FUNC-032")
    void reInvitingReplacesThePendingInvitationEvenAfterItExpired() throws Exception {
        String invited = "guest-" + UUID.randomUUID() + "@mail.com";
        String first = createdId(invite(adminToken, invited, "MEMBER", null));
        String second = createdId(invite(adminToken, invited, "ADMIN", null));

        assertThat(statusOf(first)).isEqualTo("REPLACED");
        assertThat(statusOf(second)).isEqualTo("PENDING");
        assertThat((String) JsonPath.read(eventPayload(REVOKED, first), "$.reason"))
                .isEqualTo("REPLACED");

        advanceClock(Duration.ofDays(8));
        String third = createdId(invite(adminToken, invited, "VIEWER", null));

        assertThat(statusOf(second)).isEqualTo("REPLACED");
        assertThat(statusOf(third)).isEqualTo("PENDING");
        assertThat(superuser()
                        .sql("SELECT count(*) FROM invitation WHERE workspace_id = ? AND status = 'PENDING'")
                        .param(workspaceId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @Verifies("REQ-FUNC-034")
    void onlyPendingUnexpiredInvitationsAreListedWithoutTokens() throws Exception {
        createdId(invite(adminToken, "expired-" + UUID.randomUUID() + "@mail.com", "MEMBER", null));
        advanceClock(Duration.ofDays(7).plusSeconds(1));
        String older = createdId(invite(adminToken, "older-" + UUID.randomUUID() + "@mail.com", "MEMBER", null));
        clock.advance(Duration.ofSeconds(1));
        String revoked = createdId(invite(adminToken, "revoked-" + UUID.randomUUID() + "@mail.com", "MEMBER", null));
        clock.advance(Duration.ofSeconds(1));
        String newer = createdId(invite(adminToken, "newer-" + UUID.randomUUID() + "@mail.com", "VIEWER", null));
        iam.delete(invitations() + "/" + revoked, adminToken).andExpect(status().isNoContent());

        String body = iam.get(invitations(), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> ids = JsonPath.read(body, "$.items[*].id");
        assertThat(ids).containsExactly(newer, older);
        assertThat(body)
                .doesNotContain(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)
                .doesNotContainIgnoringCase("token");
    }

    @Test
    @Verifies({"REQ-FUNC-035", "REQ-FUNC-025"})
    void aPendingInvitationIsRevokedOnceAndOthersAreRejected() throws Exception {
        String pending = createdId(invite(adminToken, "guest-" + UUID.randomUUID() + "@mail.com", "MEMBER", null));
        String accepted = createdId(invite(adminToken, "taken-" + UUID.randomUUID() + "@mail.com", "MEMBER", null));
        superuser()
                .sql("UPDATE invitation SET status = 'ACCEPTED' WHERE id = ?")
                .param(UUID.fromString(accepted))
                .update();
        UUID other = createWorkspace("Other");
        String ofOther = createdId(iam.postJson(
                API + "/workspaces/" + other + "/invitations",
                Map.of("email", "x-" + UUID.randomUUID() + "@mail.com", "role", "MEMBER"),
                ownerToken));

        iam.delete(invitations() + "/" + pending, adminToken).andExpect(status().isNoContent());

        assertThat(statusOf(pending)).isEqualTo("REVOKED");
        assertThat((String) JsonPath.read(eventPayload(REVOKED, pending), "$.reason"))
                .isEqualTo("REVOKED");
        assertThat(auditCount("INVITATION_REVOKED")).isEqualTo(1);
        iam.delete(invitations() + "/" + pending, adminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(NOT_PENDING));
        iam.delete(invitations() + "/" + accepted, adminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(NOT_PENDING));
        iam.delete(invitations() + "/" + UUID.randomUUID(), adminToken).andExpect(status().isNotFound());
        iam.delete(invitations() + "/" + ofOther, ownerToken).andExpect(status().isNotFound());
        assertThat(statusOf(ofOther)).isEqualTo("PENDING");
    }

    private record Member(UUID userId, String email, String token) {}

    /**
     * Moves the test clock and logs the ADMIN in again: access tokens live 10 minutes on the same
     * clock (REQ-INT-004), so the token from before the jump would be rejected with 401.
     */
    private void advanceClock(Duration amount) throws Exception {
        clock.advance(amount);
        adminToken = iam.login(adminEmail);
    }

    /** A new user with the given role in a workspace, seeded as the superuser. */
    private Member member(UUID workspace, String role) throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        superuser()
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

    private String invitations() {
        return API + "/workspaces/" + workspaceId + "/invitations";
    }

    private ResultActions invite(String token, String email, String role, String locale) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("role", role);
        body.put("locale", locale);
        return iam.postJson(invitations(), body, token);
    }

    private static String createdId(ResultActions result) throws Exception {
        return JsonPath.read(
                result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String statusOf(String invitationId) {
        return superuser()
                .sql("SELECT status FROM invitation WHERE id = ?")
                .param(UUID.fromString(invitationId))
                .query(String.class)
                .single();
    }

    private String eventPayload(String type, String invitationId) {
        return superuser()
                .sql("SELECT payload::text FROM outbox_event WHERE type = ? AND payload ->> 'invitationId' = ?")
                .param(type)
                .param(invitationId)
                .query(String.class)
                .single();
    }

    private long auditCount(String action) {
        return superuser()
                .sql("SELECT count(*) FROM audit_log WHERE action = ? AND workspace_id = ?")
                .param(action)
                .param(workspaceId)
                .query(Long.class)
                .single();
    }

    /** Only for seeding and inspection: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
