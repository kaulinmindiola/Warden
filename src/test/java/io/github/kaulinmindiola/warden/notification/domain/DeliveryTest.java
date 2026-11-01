package io.github.kaulinmindiola.warden.notification.domain;

import static io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus.CANCELLED;
import static io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus.DEAD;
import static io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus.PENDING;
import static io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus.RETRYING;
import static io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The delivery state machine (REQ-FUNC-058). The expected transitions are written out here from the
 * SRS, independently of the table in {@link Delivery}, and every one of the 25 pairs of states is
 * driven through the public operations.
 */
class DeliveryTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00.123456Z");
    private static final Instant RETRY_AT = NOW.plus(Duration.ofSeconds(2));

    /** REQ-FUNC-058, verbatim. */
    private static final Map<DeliveryStatus, Set<DeliveryStatus>> SRS = Map.of(
            PENDING, Set.of(SENT, RETRYING, DEAD, CANCELLED),
            RETRYING, Set.of(SENT, RETRYING, DEAD, CANCELLED),
            DEAD, Set.of(PENDING, CANCELLED),
            SENT, Set.of(),
            CANCELLED, Set.of());

    static Stream<Arguments> everyPairOfStates() {
        return Arrays.stream(DeliveryStatus.values())
                .flatMap(from -> Arrays.stream(DeliveryStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPairOfStates")
    @Verifies("REQ-FUNC-058")
    void theTransitionTableIsExactlyTheOneOfTheSrs(DeliveryStatus from, DeliveryStatus to) {
        assertThat(Delivery.isAllowed(from, to)).isEqualTo(SRS.get(from).contains(to));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPairOfStates")
    @Verifies("REQ-FUNC-058")
    void theOperationsOnlyProduceAllowedTransitions(DeliveryStatus from, DeliveryStatus to) {
        Delivery delivery = inState(from);

        if (SRS.get(from).contains(to)) {
            moveTowards(delivery, to);
            assertThat(delivery.status()).isEqualTo(to);
        } else {
            int attempts = delivery.attempts();
            assertThatThrownBy(() -> moveTowards(delivery, to))
                    .isInstanceOfSatisfying(IllegalStateTransitionException.class, e -> {
                        assertThat(e.from()).isEqualTo(from);
                        assertThat(e.to()).isEqualTo(to);
                    });
            assertThat(delivery.status()).isEqualTo(from);
            assertThat(delivery.attempts()).isEqualTo(attempts);
        }
    }

    @Test
    @Verifies("REQ-FUNC-055")
    void anAcceptedAttemptLeavesTheDeliverySentThroughTheProviderUsed() {
        Delivery delivery = inState(RETRYING);

        delivery.recordAttempt(ProviderResult.ACCEPTED, "B", NOW, null);

        assertThat(delivery.status()).isEqualTo(SENT);
        assertThat(delivery.attempts()).isEqualTo(2);
        assertThat(delivery.providerUsed()).isEqualTo("B");
        assertThat(delivery.lastErrorClass()).isNull();
        assertThat(delivery.sentAt()).isEqualTo(NOW);
    }

    @Test
    @Verifies("REQ-FUNC-056")
    void aPermanentRejectionIsDeadAfterOneAttemptWithItsCause() {
        Delivery delivery = inState(PENDING);

        delivery.recordAttempt(ProviderResult.PERMANENT_REJECTION, "A", NOW, RETRY_AT);

        assertThat(delivery.status()).isEqualTo(DEAD);
        assertThat(delivery.attempts()).isEqualTo(1);
        assertThat(delivery.lastErrorClass()).isEqualTo("PERMANENT_REJECTION");
        assertThat(delivery.sentAt()).isNull();
    }

    @Test
    @Verifies("REQ-FUNC-057")
    void fourTransientFailuresRetryAndTheFifthIsDead() {
        Delivery delivery = inState(PENDING);
        for (int attempt = 1; attempt < Delivery.MAX_ATTEMPTS; attempt++) {
            Instant retryAt = RETRY_AT.plusSeconds(attempt);
            delivery.recordAttempt(ProviderResult.TRANSIENT_FAILURE, "B", NOW, retryAt);
            assertThat(delivery.status()).isEqualTo(RETRYING);
            assertThat(delivery.attempts()).isEqualTo(attempt);
            assertThat(delivery.nextAttemptAt()).isEqualTo(retryAt);
        }

        delivery.recordAttempt(ProviderResult.TRANSIENT_FAILURE, "NONE", NOW, RETRY_AT);

        assertThat(delivery.status()).isEqualTo(DEAD);
        assertThat(delivery.attempts()).isEqualTo(Delivery.MAX_ATTEMPTS);
        assertThat(delivery.providerUsed()).isEqualTo("NONE");
        assertThat(delivery.lastErrorClass()).isEqualTo("TRANSIENT_FAILURE");
    }

    @Test
    void aRetryCannotBeDueBeforeTheFailedAttempt() {
        Delivery delivery = inState(PENDING);

        assertThatThrownBy(() -> delivery.recordAttempt(ProviderResult.TRANSIENT_FAILURE, "A", NOW, NOW.minusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(delivery.status()).isEqualTo(PENDING);
        assertThat(delivery.attempts()).isZero();
    }

    @Test
    @Verifies("REQ-FUNC-060")
    void requeueingResetsTheAttemptsAndMakesTheDeliveryDueNow() {
        Delivery delivery = Delivery.restore(
                UUID.randomUUID(), UUID.randomUUID(), DEAD, 5, NOW.minusSeconds(60), "B", "TRANSIENT_FAILURE", null);

        delivery.requeue(NOW);

        assertThat(delivery.status()).isEqualTo(PENDING);
        assertThat(delivery.attempts()).isZero();
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.lastErrorClass()).isEqualTo("TRANSIENT_FAILURE");
    }

    @Test
    @Verifies("REQ-FUNC-060")
    void aSystemScopeDeliveryCannotBeRequeued() {
        Delivery delivery = Delivery.restore(UUID.randomUUID(), null, DEAD, 5, NOW, "A", "TRANSIENT_FAILURE", null);

        assertThatThrownBy(() -> delivery.requeue(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(delivery.status()).isEqualTo(DEAD);
        assertThat(delivery.attempts()).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(DeliveryStatus.class)
    @Verifies("REQ-SEC-005")
    void theSecretIsOnlyKeptWhileTheDeliveryMayStillBeSent(DeliveryStatus status) {
        assertThat(inState(status).needsSecret()).isEqualTo(status != SENT && status != CANCELLED);
    }

    @Test
    void theAttemptsOfAStoredDeliveryAreBounded() {
        assertThatThrownBy(() -> Delivery.restore(UUID.randomUUID(), null, PENDING, -1, NOW, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Delivery.restore(UUID.randomUUID(), null, DEAD, 6, NOW, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A workspace-scope delivery in {@code status}, with one attempt unless it is PENDING. */
    private static Delivery inState(DeliveryStatus status) {
        return Delivery.restore(
                UUID.randomUUID(), UUID.randomUUID(), status, status == PENDING ? 0 : 1, NOW, null, null, null);
    }

    /** The one operation whose target is {@code to}. */
    private static void moveTowards(Delivery delivery, DeliveryStatus to) {
        switch (to) {
            case SENT -> delivery.recordAttempt(ProviderResult.ACCEPTED, "A", NOW, null);
            case RETRYING -> delivery.recordAttempt(ProviderResult.TRANSIENT_FAILURE, "A", NOW, RETRY_AT);
            case DEAD -> delivery.recordAttempt(ProviderResult.PERMANENT_REJECTION, "A", NOW, null);
            case CANCELLED -> delivery.cancel();
            case PENDING -> delivery.requeue(NOW);
        }
    }
}
