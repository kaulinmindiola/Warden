package io.github.kaulinmindiola.warden;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IamTestClient.Tokens;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-03 re-verification: the criteria of phases 1 to 3 that say a notification is recorded, or
 * that a pending one ends cancelled, checked on real deliveries instead of outbox events. Each
 * flow runs through the HTTP API, the outbox is then dispatched by the worker as in production,
 * and the deliveries are inspected as the superuser.
 */
class NotificationCriteriaIT extends IntegrationTest {

    private static final String NEW_PASSWORD = "a brand new passphrase 42";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OutboxDispatchWorker worker;

    private IamTestClient iam;

    @BeforeEach
    void setUp() {
        iam = new IamTestClient(context);
        dispatchAll();
    }

    @Test
    @Verifies("REQ-FUNC-005")
    void registeringRecordsAVerificationDeliveryWithItsSealedLink() throws Exception {
        UUID user = iam.userIdOf(iam.registerNewAccount());

        dispatchAll();

        assertThat(deliveriesOf("account.email-verification", user))
                .singleElement()
                .satisfies(delivery ->
                        assertThat(delivery).containsEntry("status", "PENDING").containsEntry("has_secret", true));
    }

    @Test
    @Verifies({"REQ-FUNC-002", "BR-007"})
    void registeringAnExistingEmailNotifiesTheAccountHolderOnce() throws Exception {
        String email = iam.registerNewAccount();
        iam.postJson(API + "/auth/register", Map.of("email", email, "password", NEW_PASSWORD), null)
                .andExpect(status().isAccepted());

        dispatchAll();

        assertThat(deliveriesOf("account.registration-attempt", iam.userIdOf(email)))
                .singleElement()
                .satisfies(delivery -> assertThat(delivery).containsEntry("status", "PENDING"));
    }

    @Test
    @Verifies({"REQ-FUNC-012", "BR-005"})
    void reusingARefreshTokenNotifiesTheSessionHolder() throws Exception {
        String email = iam.registerVerifiedAccount();
        Tokens tokens = iam.loginTokens(email);
        iam.postJson(API + "/auth/refresh", Map.of("refresh_token", tokens.refreshToken()), null)
                .andExpect(status().isOk());
        iam.postJson(API + "/auth/refresh", Map.of("refresh_token", tokens.refreshToken()), null)
                .andExpect(status().isUnauthorized());

        dispatchAll();

        assertThat(deliveriesOf("security.refresh-reuse", iam.userIdOf(email))).hasSize(1);
    }

    @Test
    @Verifies({"REQ-FUNC-019", "REQ-FUNC-020"})
    void aPasswordResetRecordsTheResetLinkAndThenTheChangeNotice() throws Exception {
        String email = iam.registerVerifiedAccount();
        UUID user = iam.userIdOf(email);
        iam.postJson(API + "/auth/password/forgot", Map.of("email", email), null)
                .andExpect(status().isAccepted());
        // The test client opens the token from the outbox event, whose secret the dispatch strips.
        String resetToken = iam.latestPasswordResetToken(user);
        dispatchAll();

        assertThat(deliveriesOf("account.password-reset", user))
                .singleElement()
                .satisfies(delivery -> assertThat(delivery).containsEntry("has_secret", true));

        iam.postJson(API + "/auth/password/reset", Map.of("token", resetToken, "newPassword", NEW_PASSWORD), null)
                .andExpect(status().isNoContent());
        dispatchAll();

        assertThat(deliveriesOf("account.password-changed", user)).hasSize(1);
    }

    @Test
    @Verifies("REQ-FUNC-031")
    void anInvitationRecordsAPendingDeliveryForTheInvitedAddress() throws Exception {
        Owner owner = ownerWithWorkspace();

        UUID invitation = invite(owner, "invitee-" + UUID.randomUUID() + "@mail.com");
        dispatchAll();

        assertThat(deliveryOfInvitation(invitation))
                .containsEntry("template_key", "workspace.invitation")
                .containsEntry("status", "PENDING")
                .containsEntry("has_secret", true);
    }

    @Test
    @Verifies("REQ-FUNC-032")
    void reInvitingCancelsThePendingDeliveryOfThePreviousInvitation() throws Exception {
        Owner owner = ownerWithWorkspace();
        String email = "invitee-" + UUID.randomUUID() + "@mail.com";
        UUID previous = invite(owner, email);
        dispatchAll();

        UUID current = invite(owner, email);
        dispatchAll();

        assertThat(deliveryOfInvitation(previous))
                .containsEntry("status", "CANCELLED")
                .containsEntry("has_secret", false);
        assertThat(deliveryOfInvitation(current)).containsEntry("status", "PENDING");
    }

    @Test
    @Verifies("REQ-FUNC-035")
    void revokingAnInvitationCancelsItsPendingDelivery() throws Exception {
        Owner owner = ownerWithWorkspace();
        UUID invitation = invite(owner, "invitee-" + UUID.randomUUID() + "@mail.com");
        dispatchAll();

        iam.delete(API + "/workspaces/" + owner.workspaceId() + "/invitations/" + invitation, owner.token())
                .andExpect(status().isNoContent());
        dispatchAll();

        assertThat(deliveryOfInvitation(invitation))
                .containsEntry("status", "CANCELLED")
                .containsEntry("has_secret", false);
    }

    @Test
    @Verifies("REQ-FUNC-030")
    void deletingAWorkspaceCancelsAllItsPendingDeliveries() throws Exception {
        Owner owner = ownerWithWorkspace();
        invite(owner, "first-" + UUID.randomUUID() + "@mail.com");
        invite(owner, "second-" + UUID.randomUUID() + "@mail.com");
        dispatchAll();

        iam.delete(API + "/workspaces/" + owner.workspaceId(), owner.token()).andExpect(status().isNoContent());
        dispatchAll();

        assertThat(statusesOfWorkspace(owner.workspaceId())).containsExactly("CANCELLED", "CANCELLED");
    }

    @Test
    @Verifies("REQ-FUNC-040")
    void aRoleChangeNotifiesTheAffectedMemberAndTheOwners() throws Exception {
        Owner owner = ownerWithWorkspace();
        UUID member = iam.userIdOf(iam.registerNewAccount());
        superuser()
                .sql(
                        "INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', now())")
                .param(owner.workspaceId())
                .param(member)
                .update();

        iam.mvc()
                .perform(MockMvcRequestBuilders.put(
                                API + "/workspaces/{w}/members/{u}/role", owner.workspaceId(), member)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk());
        dispatchAll();

        assertThat(superuser()
                        .sql("""
                                SELECT recipient_key FROM notification_delivery
                                WHERE workspace_id = ? AND template_key = 'workspace.role-changed'
                                """)
                        .param(owner.workspaceId())
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder(member.toString(), owner.userId().toString());
    }

    private Owner ownerWithWorkspace() throws Exception {
        String email = iam.registerVerifiedAccount();
        String token = iam.login(email);
        String body = iam.postJson(API + "/workspaces", Map.of("name", "Notified"), token)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Owner(iam.userIdOf(email), token, UUID.fromString(JsonPath.read(body, "$.id")));
    }

    private UUID invite(Owner owner, String email) throws Exception {
        String body = iam.postJson(
                        API + "/workspaces/" + owner.workspaceId() + "/invitations",
                        Map.of("email", email, "role", "MEMBER"),
                        owner.token())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void dispatchAll() {
        while (worker.runOnce() > 0) {
            // keep dispatching until no pending event is left
        }
    }

    private static List<Map<String, Object>> deliveriesOf(String templateKey, UUID userId) {
        return superuser()
                .sql("""
                        SELECT status, secret_ciphertext IS NOT NULL AS has_secret
                        FROM notification_delivery
                        WHERE template_key = ? AND recipient_key = ?
                        """)
                .param(templateKey)
                .param(userId.toString())
                .query()
                .listOfRows();
    }

    private static Map<String, Object> deliveryOfInvitation(UUID invitationId) {
        return superuser().sql("""
                        SELECT template_key, status, secret_ciphertext IS NOT NULL AS has_secret
                        FROM notification_delivery
                        WHERE invitation_id = ?
                        """).param(invitationId).query().singleRow();
    }

    private static List<String> statusesOfWorkspace(UUID workspaceId) {
        return superuser()
                .sql("SELECT status FROM notification_delivery WHERE workspace_id = ?")
                .param(workspaceId)
                .query(String.class)
                .list();
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }

    private record Owner(UUID userId, String token, UUID workspaceId) {}
}
