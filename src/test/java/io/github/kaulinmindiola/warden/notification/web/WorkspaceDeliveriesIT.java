package io.github.kaulinmindiola.warden.notification.web;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.notification.application.worker.DeliveryWorker;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderCircuitBreakers;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.github.kaulinmindiola.warden.workspace.InvitationAccepted;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The deliveries of a workspace and their dead-letter retry through the API (UC-23, UC-25,
 * REQ-FUNC-059, REQ-FUNC-060). Who may call them, and the 404 for other workspaces, are covered by
 * the isolation suite and the role matrix. Delivery states the worker would take minutes to reach
 * are set as the superuser.
 */
class WorkspaceDeliveriesIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    /** Seeded PENDING and RETRYING deliveries are never due, so no worker run sends them. */
    private static final Duration NOT_DUE = Duration.ofDays(3650);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private OutboxDispatchWorker outboxWorker;

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private ProviderCircuitBreakers breakers;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private IamTestClient iam;
    private String ownerEmail;
    private String ownerToken;
    private UUID workspace;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(NOW);
        iam = new IamTestClient(context);
        ownerEmail = iam.registerVerifiedAccount();
        ownerToken = iam.login(ownerEmail);
        String body = iam.postJson(API + "/workspaces", Map.of("name", "Deliveries"), ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        workspace = UUID.fromString(JsonPath.read(body, "$.id"));
        dispatchAll();
        sendAllDue();
        breakers.reset();
    }

    @Test
    @Verifies("REQ-FUNC-059")
    void theDeadFilterReturnsOnlyThisWorkspacesDeadDeliveriesWithoutTheirContent() throws Exception {
        UUID dead = seed(workspace, "DEAD");
        UUID otherDead = seed(workspace, "DEAD");
        seed(workspace, "SENT");
        seed(workspace, "PENDING");
        seed(UUID.randomUUID(), "DEAD");
        seedSystemScope("DEAD");

        String body = list("?status=DEAD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].templateKey").value("workspace.role-changed"))
                .andExpect(jsonPath("$.items[0].status").value("DEAD"))
                .andExpect(jsonPath("$.items[0].attempts").value(5))
                .andExpect(jsonPath("$.items[0].providerUsed").value("B"))
                .andExpect(jsonPath("$.items[0].lastErrorClass").value("TRANSIENT_FAILURE"))
                .andExpect(jsonPath("$.items[0].recipient").exists())
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> ids = JsonPath.read(body, "$.items[*].id");
        assertThat(ids).containsExactlyInAnyOrder(dead.toString(), otherDead.toString());
        assertThat(body)
                .doesNotContain("\"subject\"")
                .doesNotContain("\"body\"")
                .doesNotContain("\"variables\"")
                .doesNotContain("Seeded workspace")
                .doesNotContain("wot_")
                .doesNotContain("#token=");
    }

    @Test
    @Verifies("REQ-FUNC-059")
    void withoutAFilterEveryWorkspaceDeliveryIsListedNewestFirstAndSystemScopeOnesNever() throws Exception {
        UUID older = seed(workspace, "SENT");
        clock.advance(Duration.ofSeconds(1));
        UUID newer = seed(workspace, "PENDING");
        seedSystemScope("PENDING");

        String body = list("?size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.size").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String second = list("?size=1&page=1").andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(body, "$.items[0].id")).isEqualTo(newer.toString());
        assertThat(JsonPath.<String>read(second, "$.items[0].id")).isEqualTo(older.toString());
    }

    @Test
    void anUnknownStateIsAValidationError() throws Exception {
        list("?status=LOST")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:validation-error"));
    }

    @Test
    @Verifies("REQ-FUNC-060")
    void aRetriedDeadDeliveryIsPendingAgainAndSentOnceTheProvidersAnswer() throws Exception {
        UUID delivery = seed(workspace, "DEAD");

        retry(delivery)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(delivery.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(row(delivery)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
        assertThat(nextAttemptAt(delivery)).isEqualTo(NOW);

        sendAllDue();

        assertThat(row(delivery)).containsEntry("status", "SENT").containsEntry("attempts", 1);
    }

    @Test
    @Verifies({"REQ-FUNC-060", "REQ-INT-009"})
    void aDeadInvitationDeliveryOfAPendingInvitationIsSentWithItsLinkWhenRetried() throws Exception {
        UUID invitation = invite("invitee-" + UUID.randomUUID() + "@mail.test");
        UUID delivery = deliveryOfInvitation(invitation);
        markDead(delivery);

        retry(delivery).andExpect(status().isAccepted());
        sendAllDue();

        assertThat(row(delivery)).containsEntry("status", "SENT").containsEntry("has_secret", false);
        assertThat(providerA()
                        .findAll(postRequestedFor(urlPathEqualTo("/v1/messages"))
                                .withRequestBody(containing(delivery.toString()))))
                .singleElement()
                .satisfies(request -> assertThat(request.getBodyAsString())
                        .contains("http://localhost:3001/accept-invitation#token=wot_"));
    }

    @Test
    @Verifies("REQ-FUNC-060")
    void retryingTwiceIsAConflictTheSecondTime() throws Exception {
        UUID delivery = seed(workspace, "DEAD");
        retry(delivery).andExpect(status().isAccepted());

        retry(delivery)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:delivery-not-retryable"));
    }

    @Test
    @Verifies("REQ-FUNC-060")
    void aDeliveryThatIsNotDeadCannotBeRetried() throws Exception {
        for (String state : List.of("PENDING", "RETRYING", "SENT", "CANCELLED")) {
            retry(seed(workspace, state))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:warden:problem:delivery-not-retryable"));
        }
    }

    @Test
    @Verifies({"REQ-FUNC-060", "REQ-FUNC-062"})
    void theDeliveryOfARevokedInvitationCannotBeRetried() throws Exception {
        UUID invitation = invite("invitee-" + UUID.randomUUID() + "@mail.test");
        UUID delivery = deliveryOfInvitation(invitation);
        markDead(delivery);
        iam.delete(API + "/workspaces/" + workspace + "/invitations/" + invitation, ownerToken)
                .andExpect(status().isNoContent());
        dispatchAll();

        retry(delivery)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:invitation-not-pending"));
        assertThat(row(delivery)).containsEntry("status", "CANCELLED");
    }

    @Test
    @Verifies("REQ-FUNC-060")
    void theDeliveryOfAnAcceptedInvitationCannotBeRetried() throws Exception {
        UUID invitation = invite("invitee-" + UUID.randomUUID() + "@mail.test");
        UUID delivery = deliveryOfInvitation(invitation);
        markDead(delivery);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status ->
                        outbox.publish(InvitationAccepted.TYPE, workspace, new InvitationAccepted(invitation)));
        dispatchAll();

        retry(delivery)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:invitation-not-pending"));
        assertThat(row(delivery)).containsEntry("status", "CANCELLED").containsEntry("has_secret", false);
    }

    @Test
    @Verifies("REQ-FUNC-060")
    void theDeliveryOfAnExpiredInvitationCannotBeRetried() throws Exception {
        UUID invitation = invite("invitee-" + UUID.randomUUID() + "@mail.test");
        UUID delivery = deliveryOfInvitation(invitation);
        markDead(delivery);
        clock.advance(Duration.ofDays(7).plusSeconds(1));
        ownerToken = iam.login(ownerEmail);

        retry(delivery)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:invitation-not-pending"));
        assertThat(row(delivery)).containsEntry("status", "DEAD");
    }

    @Test
    @Verifies("REQ-FUNC-060")
    void aSystemScopeDeliveryOrADeliveryOfAnotherWorkspaceIsNotFound() throws Exception {
        retry(seedSystemScope("DEAD")).andExpect(status().isNotFound());
        retry(seed(UUID.randomUUID(), "DEAD")).andExpect(status().isNotFound());
        retry(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    private ResultActions list(String query) throws Exception {
        return iam.get(API + "/workspaces/" + workspace + "/notifications/deliveries" + query, ownerToken);
    }

    private ResultActions retry(UUID delivery) throws Exception {
        return iam.postJson(
                API + "/workspaces/" + workspace + "/notifications/deliveries/" + delivery + "/retry",
                Map.of(),
                ownerToken);
    }

    private UUID invite(String email) throws Exception {
        String body = iam.postJson(
                        API + "/workspaces/" + workspace + "/invitations",
                        Map.of("email", email, "role", "MEMBER"),
                        ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        dispatchAll();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void dispatchAll() {
        while (outboxWorker.runOnce() > 0) {
            // keep dispatching until no pending event is left
        }
    }

    private void sendAllDue() {
        while (deliveryWorker.runOnce() > 0) {
            // keep sending until no delivery is due
        }
    }

    /** A role-change notice of the workspace in the given state, seeded as the superuser. */
    private UUID seed(UUID workspaceId, String state) {
        return insert(workspaceId, "WORKSPACE", "workspace.role-changed", state);
    }

    /** A system-scope notice, which has no workspace. */
    private UUID seedSystemScope(String state) {
        return insert(null, "SYSTEM", "account.password-changed", state);
    }

    private UUID insert(UUID workspaceId, String scope, String templateKey, String state) {
        UUID deliveryId = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO notification_delivery (
                            id, event_id, recipient_key, channel, scope, workspace_id, template_key, locale,
                            template_version, recipient_email, variables, status, attempts, next_attempt_at,
                            provider_used, last_error_class, origin_trace_id, created_at)
                        VALUES (?, ?, ?, 'EMAIL', ?, ?, ?, 'es', 1, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, 'seeded', ?)
                        """)
                .param(deliveryId)
                .param(UUID.randomUUID())
                .param("email:seeded-" + deliveryId)
                .param(scope)
                .param(workspaceId)
                .param(templateKey)
                .param("seeded-" + deliveryId + "@mail.test")
                .param("""
                        {"workspaceName": "Seeded workspace", "affectedMember": "member@mail.test",
                         "previousRole": "MEMBER", "newRole": "ADMIN", "occurredAt": "2026-08-10 10:00 UTC"}""")
                .param(state)
                .param("PENDING".equals(state) ? 0 : 5)
                .param(OffsetDateTime.ofInstant(clock.instant().plus(NOT_DUE), ZoneOffset.UTC))
                .param("PENDING".equals(state) ? null : "B")
                .param("DEAD".equals(state) ? "TRANSIENT_FAILURE" : null)
                .param(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        return deliveryId;
    }

    private static UUID deliveryOfInvitation(UUID invitationId) {
        return superuser()
                .sql("SELECT id FROM notification_delivery WHERE invitation_id = ?")
                .param(invitationId)
                .query(UUID.class)
                .single();
    }

    /** Leaves the delivery as five failed attempts would, its token still sealed. */
    private static void markDead(UUID deliveryId) {
        superuser().sql("""
                        UPDATE notification_delivery
                        SET status = 'DEAD', attempts = 5, provider_used = 'B', last_error_class = 'TRANSIENT_FAILURE'
                        WHERE id = ?
                        """).param(deliveryId).update();
    }

    private static Map<String, Object> row(UUID deliveryId) {
        return superuser().sql("""
                        SELECT status, attempts, secret_ciphertext IS NOT NULL AS has_secret
                        FROM notification_delivery
                        WHERE id = ?
                        """).param(deliveryId).query().singleRow();
    }

    private static Instant nextAttemptAt(UUID deliveryId) {
        return superuser()
                .sql("SELECT next_attempt_at FROM notification_delivery WHERE id = ?")
                .param(deliveryId)
                .query((row, rowNumber) ->
                        row.getObject("next_attempt_at", OffsetDateTime.class).toInstant())
                .single();
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
