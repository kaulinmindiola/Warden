package io.github.kaulinmindiola.warden.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.notification.application.worker.DeliveryWorker;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.notification.application.worker.WorkerGate;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.SecondInstance;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Graceful shutdown (REQ-AVAIL-001, REQ-AVAIL-002, SDD V17): once shutdown starts, a request in
 * progress completes, new requests are refused and the workers claim nothing more, while liveness
 * answers for as long as the process serves.
 */
class GracefulShutdownIT extends IntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private WorkerGate gate;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private OutboxDispatchWorker outboxWorker;

    @Test
    @Verifies("REQ-AVAIL-002")
    void shutdownIsGracefulWithThirtySecondsPerPhase() {
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("30s");
    }

    @Test
    @Verifies({"REQ-AVAIL-001", "REQ-AVAIL-002"})
    void aRequestInProgressCompletesAndNewOnesAreRefusedOnceShutdownStarts() throws Exception {
        // The 3 s response floor keeps a password reset request in progress while the instance stops.
        SecondInstance instance = SecondInstance.withProperties(Map.of("warden.security.response-floor", "3s"));
        CompletableFuture<Void> closing = null;
        try {
            String api = instance.apiUrl();
            assertThat(send(instance.managementUrl() + "/health/liveness", null).statusCode())
                    .as("liveness while the process serves")
                    .isEqualTo(200);
            CompletableFuture<HttpResponse<String>> inProgress = HttpClient.newHttpClient()
                    .sendAsync(
                            post(
                                    api + "/auth/password/forgot",
                                    "{\"email\":\"shutdown-" + UUID.randomUUID() + "@mail.test\"}"),
                            HttpResponse.BodyHandlers.ofString());
            Thread.sleep(500);

            closing = CompletableFuture.runAsync(instance::close);
            boolean refused = false;
            Instant deadline = Instant.now().plusSeconds(2);
            while (!refused && Instant.now().isBefore(deadline)) {
                refused = isRefused(api + "/auth/sessions");
                if (!refused) {
                    Thread.sleep(50);
                }
            }

            assertThat(refused)
                    .as("a new request while shutting down is refused")
                    .isTrue();
            assertThat(inProgress.get(10, TimeUnit.SECONDS).statusCode())
                    .as("the request in progress completes")
                    .isEqualTo(202);
            closing.get(40, TimeUnit.SECONDS);
        } finally {
            if (closing == null) {
                instance.close();
            }
        }
    }

    @Test
    @Verifies("REQ-AVAIL-002")
    void onceTheGateClosesTheWorkersClaimNothingAndTheDeliveryWaitsForAnotherRun() {
        Instant due = clock.instant().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        UUID delivery = seedPending(due);

        gate.stop();
        try {
            assertThat(deliveryWorker.runOnce()).isZero();
            assertThat(outboxWorker.runOnce()).isZero();
            assertThat(state(delivery))
                    .containsEntry("status", "PENDING")
                    .containsEntry("attempts", 0)
                    .containsEntry("leased", false);
        } finally {
            gate.start();
        }

        while (deliveryWorker.runOnce() > 0) {
            // an open gate sends whatever is due
        }
        assertThat(state(delivery)).containsEntry("status", "SENT");
    }

    /** Whether a new request is refused: no connection, no answer in time, or 503. */
    private static boolean isRefused(String url) throws InterruptedException {
        try {
            return send(url, Duration.ofSeconds(1)).statusCode() == 503;
        } catch (IOException refused) {
            return true;
        }
    }

    private static HttpResponse<String> send(String url, Duration timeout) throws IOException, InterruptedException {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (timeout != null) {
            request.timeout(timeout);
        }
        try (client) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpRequest post(String url, String json) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    /** A role-change notice due at {@code dueAt}, without link. */
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
                        {"workspaceName": "Draining", "affectedMember": "member@mail.test",
                         "previousRole": "MEMBER", "newRole": "ADMIN"}""")
                .param(due)
                .param(due)
                .update();
        return deliveryId;
    }

    private static Map<String, Object> state(UUID deliveryId) {
        return superuser()
                .sql(
                        "SELECT status, attempts, lease_until IS NOT NULL AS leased FROM notification_delivery WHERE id = ?")
                .param(deliveryId)
                .query()
                .singleRow();
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
