package io.github.kaulinmindiola.warden.notification.application.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.kaulinmindiola.warden.notification.domain.ClaimedDelivery;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderCircuitBreakers;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.github.kaulinmindiola.warden.workspace.InvitationRevoked;
import io.github.kaulinmindiola.warden.workspace.MemberInvited;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The delivery worker against PostgreSQL and the simulated providers (SDD V14-A5): the recipient's
 * domain decides the provider's answer (ops/providers). Deliveries are created by real invitation
 * events and inspected as the superuser.
 *
 * <p>Other tests leave pending deliveries behind in the shared database, so each test first sends
 * whatever is due and then only looks at its own deliveries.
 */
class DeliveryWorkerIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");
    private static final Instant EXPIRES = NOW.plus(Duration.ofDays(7));
    private static final UUID WORKSPACE = UUID.randomUUID();

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private OutboxDispatchWorker outboxWorker;

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private ProviderCircuitBreakers breakers;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void sendWhatOtherTestsLeftDue() {
        clock.setInstant(NOW);
        dispatchAll();
        sendAllDue();
        breakers.reset();
    }

    @Test
    @Verifies({"REQ-FUNC-055", "REQ-INT-009", "REQ-SEC-005"})
    void anAcceptedDeliveryIsSentWithTheLinkInTheFragmentAndLosesItsToken() {
        UUID invitation = invite("ana-" + UUID.randomUUID() + "@mail.test");

        sendAllDue();

        Row row = deliveryOf(invitation);
        assertThat(row.status()).isEqualTo("SENT");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.providerUsed()).isEqualTo("A");
        assertThat(row.sentAt()).isEqualTo(NOW);
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.hasSecret()).isFalse();
        List<LoggedRequest> sent = requestsFor(providerA(), row.id());
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getBodyAsString())
                .contains("http://localhost:3001/accept-invitation#token=wot_" + invitation)
                .doesNotContain("?token=");
        assertThat(requestsFor(providerB(), row.id())).isEmpty();
    }

    @Test
    @Verifies({"REQ-FUNC-057", "REQ-REL-006"})
    void withBothProvidersFailingTheDeliveryBacksOffAndIsDeadAfterFiveAttempts() {
        UUID invitation = invite("ana-" + UUID.randomUUID() + "@unavailable.test");

        for (int attempt = 1; attempt < 5; attempt++) {
            Instant attemptedAt = clock.instant();
            sendAllDue();
            Row row = deliveryOf(invitation);
            assertThat(row.status()).isEqualTo("RETRYING");
            assertThat(row.attempts()).isEqualTo(attempt);
            assertThat(row.providerUsed()).isEqualTo("B");
            long base = 1000L << (attempt - 1);
            assertThat(Duration.between(attemptedAt, row.nextAttemptAt()).toMillis())
                    .as("wait after attempt %d", attempt)
                    .isBetween(base / 2, base * 3 / 2);

            clock.setInstant(row.nextAttemptAt().minusMillis(1));
            sendAllDue();
            assertThat(deliveryOf(invitation).attempts()).as("not due yet").isEqualTo(attempt);
            clock.setInstant(row.nextAttemptAt());
        }
        sendAllDue();

        Row dead = deliveryOf(invitation);
        assertThat(dead.status()).isEqualTo("DEAD");
        assertThat(dead.attempts()).isEqualTo(5);
        assertThat(dead.lastErrorClass()).isEqualTo("TRANSIENT_FAILURE");
        assertThat(dead.hasSecret()).as("kept for a manual retry").isTrue();
        assertThat(requestsFor(providerA(), dead.id())).hasSize(5);
        assertThat(requestsFor(providerB(), dead.id())).hasSize(5);
    }

    @Test
    @Verifies("REQ-FUNC-056")
    void aPermanentRejectionIsDeadAfterOneAttemptWithoutFailover() {
        UUID invitation = invite("ana-" + UUID.randomUUID() + "@invalid.test");

        sendAllDue();

        Row row = deliveryOf(invitation);
        assertThat(row.status()).isEqualTo("DEAD");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.providerUsed()).isEqualTo("A");
        assertThat(row.lastErrorClass()).isEqualTo("PERMANENT_REJECTION");
        assertThat(requestsFor(providerB(), row.id())).isEmpty();
    }

    @Test
    @Verifies("REQ-SEC-005")
    void aLinkThatExpiredBeforeItWasSentIsNotSentAndItsTokenIsDropped() {
        UUID invitation = invite("ana-" + UUID.randomUUID() + "@mail.test");
        clock.setInstant(EXPIRES);

        sendAllDue();

        Row row = deliveryOf(invitation);
        assertThat(row.status()).isEqualTo("DEAD");
        assertThat(row.attempts()).isZero();
        assertThat(row.lastErrorClass()).isEqualTo(DeliveryWorker.LINK_EXPIRED);
        assertThat(row.hasSecret()).isFalse();
        assertThat(requestsFor(providerA(), row.id())).isEmpty();
        assertThat(requestsFor(providerB(), row.id())).isEmpty();
    }

    @Test
    @Verifies("REQ-SEC-005")
    void theTokenOfADeadDeliveryIsDroppedOnceItExpires() {
        UUID invitation = invite("ana-" + UUID.randomUUID() + "@invalid.test");
        sendAllDue();
        assertThat(deliveryOf(invitation).hasSecret()).isTrue();

        clock.setInstant(EXPIRES);
        deliveryWorker.runOnce();

        assertThat(deliveryOf(invitation).hasSecret()).isFalse();
        assertThat(deliveryOf(invitation).status()).isEqualTo("DEAD");
    }

    @Test
    void aClaimedDeliveryIsLeasedFor30SecondsAndOnlyClaimedAgainOnceTheLeaseLapses() {
        UUID invitation = invite("ana-" + UUID.randomUUID() + "@mail.test");
        UUID delivery = deliveryOf(invitation).id();

        assertThat(idsOf(deliveryWorker.claim())).contains(delivery);
        assertThat(deliveryOf(invitation).leaseUntil()).isEqualTo(NOW.plusSeconds(30));
        assertThat(idsOf(deliveryWorker.claim())).doesNotContain(delivery);
        clock.setInstant(NOW.plusSeconds(30));
        assertThat(idsOf(deliveryWorker.claim())).doesNotContain(delivery);

        clock.setInstant(NOW.plusSeconds(31));
        List<ClaimedDelivery> again = deliveryWorker.claim();

        assertThat(idsOf(again)).contains(delivery);
        again.forEach(deliveryWorker::attempt);
        assertThat(deliveryOf(invitation).status()).isEqualTo("SENT");
    }

    @Test
    @Verifies({"REQ-FUNC-062", "REQ-SEC-005"})
    void aDeliveryCancelledWhileItWasBeingSentStaysCancelled() {
        UUID invitation = invite("ana-" + UUID.randomUUID() + "@mail.test");
        UUID delivery = deliveryOf(invitation).id();
        ClaimedDelivery claimed = deliveryWorker.claim().stream()
                .filter(candidate -> candidate.id().equals(delivery))
                .findFirst()
                .orElseThrow();

        publish(InvitationRevoked.TYPE, new InvitationRevoked(invitation, InvitationRevoked.Reason.REVOKED));
        dispatchAll();
        deliveryWorker.attempt(claimed);

        Row row = deliveryOf(invitation);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.attempts()).isZero();
        assertThat(row.hasSecret()).isFalse();
    }

    private UUID invite(String email) {
        UUID invitationId = UUID.randomUUID();
        publish(
                MemberInvited.TYPE,
                new MemberInvited(
                        invitationId,
                        "Acme",
                        Role.MEMBER,
                        new Recipient(null, email, "es"),
                        secretCipher.seal("wot_" + invitationId, EXPIRES),
                        EXPIRES));
        dispatchAll();
        return invitationId;
    }

    private void publish(String type, Object payload) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> outbox.publish(type, WORKSPACE, payload));
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

    private static List<UUID> idsOf(List<ClaimedDelivery> claimed) {
        return claimed.stream().map(ClaimedDelivery::id).toList();
    }

    private static List<LoggedRequest> requestsFor(WireMockServer provider, UUID deliveryId) {
        return provider.findAll(
                postRequestedFor(urlPathEqualTo("/v1/messages")).withRequestBody(containing(deliveryId.toString())));
    }

    private static Row deliveryOf(UUID invitationId) {
        return JdbcClient.create(superuserDataSource())
                .sql("""
                        SELECT id, status, attempts, provider_used, last_error_class, next_attempt_at,
                               lease_until, sent_at, secret_ciphertext IS NOT NULL AS has_secret
                        FROM notification_delivery
                        WHERE invitation_id = ?
                        """)
                .param(invitationId)
                .query(Row::of)
                .single();
    }

    /** One delivery as stored. */
    private record Row(
            UUID id,
            String status,
            int attempts,
            String providerUsed,
            String lastErrorClass,
            Instant nextAttemptAt,
            Instant leaseUntil,
            Instant sentAt,
            boolean hasSecret) {

        static Row of(ResultSet row, int rowNumber) throws SQLException {
            return new Row(
                    row.getObject("id", UUID.class),
                    row.getString("status"),
                    row.getInt("attempts"),
                    row.getString("provider_used"),
                    row.getString("last_error_class"),
                    instant(row, "next_attempt_at"),
                    instant(row, "lease_until"),
                    instant(row, "sent_at"),
                    row.getBoolean("has_secret"));
        }

        private static Instant instant(ResultSet row, String column) throws SQLException {
            OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }
    }
}
