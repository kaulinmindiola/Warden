package io.github.kaulinmindiola.warden.notification.application.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.github.kaulinmindiola.warden.notification.domain.EmailProviders;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderCircuitBreakers;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderMetrics;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxDispatcher;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxMetrics;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * Notification and outbox metrics (REQ-OBS-002, SDD V19). Counters and gauges are read as
 * differences, since the shared context accumulates the work of every other test.
 *
 * <p>Outages are stubs of a higher priority than the mappings of ops/providers, removed after each
 * test, so the shared providers keep their usual answers for other tests.
 */
class NotificationMetricsIT extends IntegrationTest {

    private static final int DELIVERIES = 10;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private OutboxDispatchWorker outboxWorker;

    @Autowired
    private ProviderCircuitBreakers breakers;

    @Autowired
    private EmailProviders providers;

    @Autowired
    private DeliveryGauges deliveryGauges;

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private MeterRegistry meterRegistry;

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
    @Verifies({"REQ-OBS-002", "REQ-REL-004"})
    void withBothProvidersDownTheBreakersShowOpenAndTheSystemDeadLetterQueueGrows() {
        String primary = providers.primary().name();
        String secondary = providers.secondary().name();
        bothProvidersAnswer503();
        deliveryGauges.refresh();
        double deadBefore = deliveries("dead", "system");
        double primaryFailures = attempts(primary, "transient_failure");
        double secondaryFailures = attempts(secondary, "transient_failure");
        long primaryTimed = requests(primary, "transient_failure").count();
        Instant due = clock.instant().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        for (int delivery = 0; delivery < DELIVERIES; delivery++) {
            seedLastSystemAttempt(due);
        }

        sendAllDue();
        deliveryGauges.refresh();

        for (String provider : new String[] {primary, secondary}) {
            assertThat(circuit(provider, "open"))
                    .as("open breaker of %s", provider)
                    .isEqualTo(1);
            assertThat(circuit(provider, "closed")).isZero();
            assertThat(circuit(provider, "half_open")).isZero();
        }
        assertThat(deliveries("dead", "system") - deadBefore)
                .as("system-scope dead-letter queue")
                .isEqualTo(DELIVERIES);
        assertThat(attempts(primary, "transient_failure") - primaryFailures).isEqualTo(DELIVERIES);
        assertThat(attempts(secondary, "transient_failure") - secondaryFailures).isEqualTo(DELIVERIES);
        assertThat(requests(primary, "transient_failure").count() - primaryTimed)
                .isEqualTo(DELIVERIES);
    }

    @Test
    @Verifies("REQ-OBS-002")
    void aSentWorkspaceDeliveryMovesBetweenStatusesAndIsAnAcceptedAttemptOfThePrimary() {
        String primary = providers.primary().name();
        deliveryGauges.refresh();
        double pendingBefore = deliveries("pending", "workspace");
        double sentBefore = deliveries("sent", "workspace");
        double accepted = attempts(primary, "accepted");
        long timed = requests(primary, "accepted").count();

        seedPendingWorkspaceDelivery(clock.instant().minusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        deliveryGauges.refresh();
        assertThat(deliveries("pending", "workspace") - pendingBefore).isEqualTo(1);

        sendAllDue();
        deliveryGauges.refresh();

        assertThat(deliveries("pending", "workspace")).isEqualTo(pendingBefore);
        assertThat(deliveries("sent", "workspace") - sentBefore).isEqualTo(1);
        assertThat(attempts(primary, "accepted") - accepted).isEqualTo(1);
        assertThat(requests(primary, "accepted").count() - timed).isEqualTo(1);
        assertThat(circuit(primary, "closed")).isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-OBS-002", "REQ-PERF-004"})
    void pendingEventsAndTheTimeToPublishThemAreMeasured() throws Exception {
        long pendingBefore = outboxMetrics.refreshPending();
        long lagBefore = lag().count();

        new IamTestClient(context).registerNewAccount();
        long pending = outboxMetrics.refreshPending();
        assertThat(pending).isGreaterThan(pendingBefore);
        assertThat(meterRegistry.get(OutboxMetrics.PENDING_METRIC).gauge().value())
                .isEqualTo(pending);

        int dispatched = dispatchAll();

        assertThat(outboxMetrics.refreshPending()).isEqualTo(pendingBefore);
        assertThat((long) dispatched).isEqualTo(pending - pendingBefore);
        assertThat(lag().count() - lagBefore).isEqualTo(dispatched);
        assertThat(Arrays.stream(lag().takeSnapshot().histogramCounts()).map(bucket -> bucket.bucket(TimeUnit.SECONDS)))
                .as("a bucket at the 5 s target of REQ-PERF-004")
                .contains(5.0);
    }

    /** Both providers answer 503 at once, so the breakers fill up without waiting for timeouts. */
    private void bothProvidersAnswer503() {
        outageA = providerA()
                .stubFor(post(urlPathEqualTo("/v1/messages"))
                        .atPriority(1)
                        .willReturn(aResponse().withStatus(503)));
        outageB = providerB()
                .stubFor(post(urlPathEqualTo("/v1/messages"))
                        .atPriority(1)
                        .willReturn(aResponse().withStatus(503)));
    }

    private int dispatchAll() {
        int dispatched = 0;
        int batch;
        while ((batch = outboxWorker.runOnce()) > 0) {
            dispatched += batch;
        }
        return dispatched;
    }

    private void sendAllDue() {
        while (deliveryWorker.runOnce() > 0) {
            // keep sending until no delivery is due
        }
    }

    /** A system-scope password-changed notice whose next attempt, due at {@code dueAt}, is its last. */
    private static void seedLastSystemAttempt(Instant dueAt) {
        UUID deliveryId = UUID.randomUUID();
        OffsetDateTime due = OffsetDateTime.ofInstant(dueAt, ZoneOffset.UTC);
        superuser()
                .sql("""
                        INSERT INTO notification_delivery (
                            id, event_id, recipient_key, channel, scope, workspace_id, template_key, locale,
                            template_version, recipient_email, variables, status, attempts, next_attempt_at,
                            origin_trace_id, created_at)
                        VALUES (?, ?, ?, 'EMAIL', 'SYSTEM', NULL, 'account.password-changed', 'es',
                                1, ?, CAST(? AS jsonb), 'RETRYING', 4, ?, 'seeded', ?)
                        """)
                .param(deliveryId)
                .param(UUID.randomUUID())
                .param("email:seeded-" + deliveryId)
                .param("seeded-" + deliveryId + "@mail.test")
                .param("{\"occurredAt\": \"2026-08-17T10:00:00Z\"}")
                .param(due)
                .param(due)
                .update();
    }

    /** A workspace role-change notice due at {@code dueAt}, without link. */
    private static void seedPendingWorkspaceDelivery(Instant dueAt) {
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
                        {"workspaceName": "Measured", "affectedMember": "member@mail.test",
                         "previousRole": "MEMBER", "newRole": "ADMIN"}""")
                .param(due)
                .param(due)
                .update();
    }

    private double deliveries(String status, String scope) {
        return meterRegistry
                .get(DeliveryGauges.DELIVERIES_METRIC)
                .tags(DeliveryGauges.STATUS_TAG, status, DeliveryGauges.SCOPE_TAG, scope)
                .gauge()
                .value();
    }

    private double attempts(String provider, String result) {
        return meterRegistry
                .get(ProviderMetrics.ATTEMPTS)
                .tags(ProviderMetrics.PROVIDER_TAG, provider, ProviderMetrics.RESULT_TAG, result)
                .counter()
                .count();
    }

    private Timer requests(String provider, String result) {
        return meterRegistry
                .get(ProviderMetrics.REQUESTS)
                .tags(ProviderMetrics.PROVIDER_TAG, provider, ProviderMetrics.RESULT_TAG, result)
                .timer();
    }

    private double circuit(String provider, String state) {
        return meterRegistry
                .get(ProviderMetrics.CIRCUIT_STATE)
                .tags(ProviderMetrics.PROVIDER_TAG, provider, ProviderMetrics.STATE_TAG, state)
                .gauge()
                .value();
    }

    private Timer lag() {
        return meterRegistry.get(OutboxDispatcher.LAG_METRIC).timer();
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
