package io.github.kaulinmindiola.warden.notification.application.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.notification.domain.ClaimedDelivery;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderCircuitBreakers;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.SecondInstance;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * The notification pipeline under failure (SDD V13 failure paths, ADR-009, ADR-015): business
 * operations ignore a provider outage, deliveries survive a restart, and a lapsed lease lets
 * another run send the delivery again with the same idempotency key.
 *
 * <p>Outages are simulated with stubs of a higher priority than the mappings of ops/providers,
 * removed after each test, so the shared providers keep their usual answers for other tests.
 */
class DeliveryResilienceIT extends IntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private OutboxDispatchWorker outboxWorker;

    @Autowired
    private ProviderCircuitBreakers breakers;

    private StubMapping outageA;
    private StubMapping outageB;

    @BeforeEach
    void sendWhatOtherTestsLeftDue() {
        dispatchAll();
        sendAllDue();
        breakers.reset();
    }

    @AfterEach
    void restoreTheProviders() {
        if (outageA != null) {
            providerA().removeStub(outageA);
            outageA = null;
        }
        if (outageB != null) {
            providerB().removeStub(outageB);
            outageB = null;
        }
        breakers.reset();
    }

    @Test
    @Verifies("REQ-REL-003")
    void withBothProvidersDownLoginRefreshInvitationsAndNotesAnswerAsUsualAndDeliveriesStayPending() throws Exception {
        bothProvidersDown();
        IamTestClient iam = new IamTestClient(context);

        String email = iam.registerVerifiedAccount();
        IamTestClient.Tokens tokens = iam.loginTokens(email);
        String access = JsonPath.read(
                iam.postJson(API + "/auth/refresh", Map.of("refresh_token", tokens.refreshToken()), null)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.access_token");
        UUID workspace = UUID.fromString(JsonPath.read(
                iam.postJson(API + "/workspaces", Map.of("name", "Outage"), access)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        UUID invitation = UUID.fromString(JsonPath.read(
                iam.postJson(
                                API + "/workspaces/" + workspace + "/invitations",
                                Map.of("email", "invitee-" + UUID.randomUUID() + "@mail.test", "role", "MEMBER"),
                                access)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        iam.postJson(
                        API + "/workspaces/" + workspace + "/notes",
                        Map.of("title", "Written during the outage", "body", "Body"),
                        access)
                .andExpect(status().isCreated());
        iam.get(API + "/workspaces/" + workspace + "/notes", access).andExpect(status().isOk());

        dispatchAll();
        deliveryWorker.runOnce();

        Map<String, Object> delivery = superuser()
                .sql("SELECT status, attempts FROM notification_delivery WHERE invitation_id = ?")
                .param(invitation)
                .query()
                .singleRow();
        assertThat(delivery).containsEntry("status", "RETRYING").containsEntry("attempts", 1);
    }

    @Test
    @Verifies("REQ-AVAIL-003")
    void aRetryingDeliveryIsSentByAnInstanceStartedAfterTheOneThatFailedIt() {
        Instant past = Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MICROS);
        clock.setInstant(past);
        UUID delivery = seedPending(past);
        bothProvidersDown();
        sendAllDue();
        assertThat(state(delivery)).containsEntry("status", "RETRYING").containsEntry("attempts", 1);
        restoreTheProviders();

        try (SecondInstance restarted = SecondInstance.start()) {
            DeliveryWorker worker = restarted.bean(DeliveryWorker.class);
            while (worker.runOnce() > 0) {
                // the restarted instance sends whatever is due by its real clock
            }
        }

        assertThat(state(delivery)).containsEntry("status", "SENT").containsEntry("attempts", 2);
    }

    @Test
    @Verifies({"REQ-AVAIL-003", "REQ-REL-002"})
    void whenALeaseLapsesAnotherRunSendsTheDeliveryAgainWithTheSameIdempotencyKey() {
        Instant now = Instant.parse("2026-08-17T10:00:00Z");
        clock.setInstant(now);
        UUID delivery = seedPending(now);
        ClaimedDelivery byCrashedWorker = claimed(deliveryWorker.claim(), delivery);
        assertThat(deliveryWorker.claim()).extracting(ClaimedDelivery::id).doesNotContain(delivery);

        clock.setInstant(now.plusSeconds(31));
        ClaimedDelivery byAnotherRun = claimed(deliveryWorker.claim(), delivery);
        deliveryWorker.attempt(byAnotherRun);
        deliveryWorker.attempt(byCrashedWorker);

        assertThat(state(delivery)).containsEntry("status", "SENT").containsEntry("attempts", 1);
        assertThat(providerA()
                        .findAll(postRequestedFor(urlPathEqualTo("/v1/messages"))
                                .withRequestBody(matchingJsonPath("$.idempotencyKey", equalTo(delivery.toString())))))
                .as("both sends carry the delivery id, so the provider can drop the second")
                .hasSize(2);
    }

    /** Provider A times out (6 s against a 5 s limit) and provider B answers 503. */
    private void bothProvidersDown() {
        outageA = providerA()
                .stubFor(post(urlPathEqualTo("/v1/messages"))
                        .atPriority(1)
                        .willReturn(aResponse().withStatus(202).withFixedDelay(6000)));
        outageB = providerB()
                .stubFor(post(urlPathEqualTo("/v1/messages"))
                        .atPriority(1)
                        .willReturn(aResponse().withStatus(503)));
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

    private static ClaimedDelivery claimed(List<ClaimedDelivery> claimed, UUID delivery) {
        return claimed.stream()
                .filter(candidate -> candidate.id().equals(delivery))
                .findFirst()
                .orElseThrow(() -> new AssertionError("delivery not claimed"));
    }

    /** A role-change notice due at {@code dueAt}, without link, so any instance can render it. */
    private static UUID seedPending(Instant dueAt) {
        UUID deliveryId = UUID.randomUUID();
        OffsetDateTime due = OffsetDateTime.ofInstant(dueAt, ZoneOffset.UTC);
        superuser()
                .sql("""
                        INSERT INTO notification_delivery (
                            id, event_id, recipient_key, channel, scope, workspace_id, template_key, locale,
                            template_version, recipient_email, variables, status, attempts, next_attempt_at,
                            origin_trace_id, created_at)
                        VALUES (?, ?, ?, 'EMAIL', 'WORKSPACE', ?, 'workspace.role-changed', 'es',
                                1, ?, CAST(? AS jsonb), 'PENDING', 0, ?, 'seeded', ?)
                        """)
                .param(deliveryId)
                .param(UUID.randomUUID())
                .param("email:seeded-" + deliveryId)
                .param(UUID.randomUUID())
                .param("seeded-" + deliveryId + "@mail.test")
                .param("""
                        {"workspaceName": "Resilient", "affectedMember": "member@mail.test",
                         "previousRole": "MEMBER", "newRole": "ADMIN"}""")
                .param(due)
                .param(due)
                .update();
        return deliveryId;
    }

    private static Map<String, Object> state(UUID deliveryId) {
        return superuser()
                .sql("SELECT status, attempts FROM notification_delivery WHERE id = ?")
                .param(deliveryId)
                .query()
                .singleRow();
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
