package io.github.kaulinmindiola.warden;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.notification.application.worker.DeliveryWorker;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderCircuitBreakers;
import io.github.kaulinmindiola.warden.shared.crypto.KeyMaterial;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.SecondInstance;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.github.kaulinmindiola.warden.workspace.MemberRoleChanged;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two instances of Warden on the same PostgreSQL, Redis and providers (REQ-REL-002,
 * REQ-DIST-001): the shared test context, reached through MockMvc, and a {@link SecondInstance}
 * started with the same keys, reached over HTTP. Nothing is shared in memory between them.
 */
class TwoInstancesIT extends IntegrationTest {

    private static final int EVENTS = 1000;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private KeyMaterial keys;

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private OutboxDispatchWorker outboxWorker;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private ProviderCircuitBreakers breakers;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void sendWhatOtherTestsLeftDue() {
        clock.setInstant(Instant.now());
        drain(outboxWorker::runOnce);
        drain(deliveryWorker::runOnce);
        breakers.reset();
    }

    @Test
    @Verifies({"REQ-REL-002", "REQ-DIST-001"})
    void aThousandEventsHandledByTwoInstancesAtOnceProduceAndSendEachDeliveryExactlyOnce() throws Exception {
        UUID workspace = UUID.randomUUID();
        String marker = "Multi-" + workspace;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int event = 0; event < EVENTS; event++) {
                outbox.publish(
                        MemberRoleChanged.TYPE,
                        workspace,
                        new MemberRoleChanged(
                                marker,
                                new Recipient(UUID.randomUUID(), "member-" + event + "@mail.test", "es"),
                                List.of(),
                                Role.MEMBER,
                                Role.ADMIN,
                                UUID.randomUUID()));
            }
        });

        try (SecondInstance second = SecondInstance.start(keys)) {
            int[] dispatched = inParallel(outboxWorker::runOnce, second.bean(OutboxDispatchWorker.class)::runOnce);
            System.out.printf(
                    "Outbox events dispatched: %d here, %d by the second instance%n", dispatched[0], dispatched[1]);

            assertThat(dispatched[0] + dispatched[1]).isGreaterThanOrEqualTo(EVENTS);
            assertThat(count(
                            "SELECT count(*) FROM outbox_event WHERE workspace_id = ? AND published_at IS NULL",
                            workspace))
                    .isZero();
            Map<String, Object> created =
                    superuser().sql("""
                            SELECT count(*) AS deliveries, count(DISTINCT event_id) AS events,
                                   count(DISTINCT recipient_key) AS recipients
                            FROM notification_delivery
                            WHERE workspace_id = ?
                            """).param(workspace).query().singleRow();
            assertThat(created)
                    .containsEntry("deliveries", (long) EVENTS)
                    .containsEntry("events", (long) EVENTS)
                    .containsEntry("recipients", (long) EVENTS);

            clock.setInstant(Instant.now());
            int[] claimed = inParallel(deliveryWorker::runOnce, second.bean(DeliveryWorker.class)::runOnce);
            System.out.printf("Deliveries claimed: %d here, %d by the second instance%n", claimed[0], claimed[1]);
        }

        assertThat(count(
                        "SELECT count(*) FROM notification_delivery WHERE workspace_id = ? AND status = 'SENT'",
                        workspace))
                .isEqualTo(EVENTS);
        assertThat(count("SELECT max(attempts) FROM notification_delivery WHERE workspace_id = ?", workspace))
                .isEqualTo(1);
        List<LoggedRequest> sent = new ArrayList<>();
        sent.addAll(providerA()
                .findAll(postRequestedFor(urlPathEqualTo("/v1/messages")).withRequestBody(containing(marker))));
        sent.addAll(providerB()
                .findAll(postRequestedFor(urlPathEqualTo("/v1/messages")).withRequestBody(containing(marker))));
        HashSet<String> keysSent = new HashSet<>();
        sent.forEach(request -> keysSent.add(JsonPath.read(request.getBodyAsString(), "$.idempotencyKey")));
        assertThat(sent).as("messages sent").hasSize(EVENTS);
        assertThat(keysSent).as("distinct deliveries sent").hasSize(EVENTS);
    }

    @Test
    @Verifies({"REQ-DIST-001", "REQ-FUNC-015"})
    void loginRefreshAndLogoutAlternateBetweenInstancesAndTheRevocationHoldsOnBoth() throws Exception {
        IamTestClient iam = new IamTestClient(context);
        String email = iam.registerVerifiedAccount();

        try (SecondInstance second = SecondInstance.start(keys)) {
            HttpResponse<String> login =
                    post(second, "/auth/login", Map.of("email", email, "password", IamTestClient.PASSWORD), null);
            assertThat(login.statusCode()).as("login on the second instance").isEqualTo(200);

            clock.setInstant(Instant.now());
            String refreshed = iam.postJson(
                            API + "/auth/refresh",
                            Map.of("refresh_token", JsonPath.<String>read(login.body(), "$.refresh_token")),
                            null)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String access = JsonPath.read(refreshed, "$.access_token");
            String refresh = JsonPath.read(refreshed, "$.refresh_token");

            assertThat(get(second, "/auth/sessions", access).statusCode())
                    .as("token issued here, used on the second instance")
                    .isEqualTo(200);
            assertThat(post(second, "/auth/logout", Map.of(), access).statusCode())
                    .as("logout on the second instance")
                    .isEqualTo(204);

            clock.setInstant(Instant.now());
            iam.get(API + "/auth/sessions", access).andExpect(status().isUnauthorized());
            iam.postJson(API + "/auth/refresh", Map.of("refresh_token", refresh), null)
                    .andExpect(status().isUnauthorized());
            assertThat(get(second, "/auth/sessions", access).statusCode())
                    .as("revoked on the second instance too")
                    .isEqualTo(401);
            assertThat(post(second, "/auth/refresh", Map.of("refresh_token", refresh), null)
                            .statusCode())
                    .isEqualTo(401);
        }
    }

    /** Runs both workers at the same time, each until it finds nothing left, and returns their totals. */
    private static int[] inParallel(IntSupplier here, IntSupplier there) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> drain(here));
            Future<Integer> second = executor.submit(() -> drain(there));
            return new int[] {first.get(), second.get()};
        }
    }

    private static int drain(IntSupplier worker) {
        int total = 0;
        for (int batch = worker.getAsInt(); batch > 0; batch = worker.getAsInt()) {
            total += batch;
        }
        return total;
    }

    private HttpResponse<String> post(SecondInstance instance, String path, Map<String, ?> body, String accessToken)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(instance.apiUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(SecondInstance instance, String path, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(instance.apiUrl() + path))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long count(String sql, UUID workspace) {
        Long value = superuser().sql(sql).param(workspace).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
