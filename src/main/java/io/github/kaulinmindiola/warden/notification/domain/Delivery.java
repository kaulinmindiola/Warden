package io.github.kaulinmindiola.warden.notification.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The life cycle of a delivery (REQ-FUNC-058, SDD V15). Its state only changes through {@link
 * #recordAttempt}, {@link #cancel} and {@link #requeue}, each checked against {@link #ALLOWED}; any
 * other move throws {@link IllegalStateTransitionException} and leaves the delivery untouched.
 *
 * <p>The content of the delivery (recipient, template, variables, sealed secret) is not part of the
 * state machine and stays in the repository.
 */
public final class Delivery {

    /** Attempts per cycle before a delivery is moved to {@link DeliveryStatus#DEAD} (REQ-FUNC-057). */
    public static final int MAX_ATTEMPTS = 5;

    /** The transitions of REQ-FUNC-058; SENT and CANCELLED are terminal. */
    private static final Map<DeliveryStatus, Set<DeliveryStatus>> ALLOWED = allowedTransitions();

    private final UUID id;
    private final UUID workspaceId;
    private DeliveryStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private String providerUsed;
    private String lastErrorClass;
    private Instant sentAt;

    private Delivery(
            UUID id,
            UUID workspaceId,
            DeliveryStatus status,
            int attempts,
            Instant nextAttemptAt,
            String providerUsed,
            String lastErrorClass,
            Instant sentAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.providerUsed = providerUsed;
        this.lastErrorClass = lastErrorClass;
        this.sentAt = sentAt;
    }

    /**
     * A delivery as stored.
     *
     * @param workspaceId the workspace of a workspace-scope delivery, {@code null} for system scope
     * @throws IllegalArgumentException if the attempts are outside 0..{@value #MAX_ATTEMPTS}
     */
    public static Delivery restore(
            UUID id,
            UUID workspaceId,
            DeliveryStatus status,
            int attempts,
            Instant nextAttemptAt,
            String providerUsed,
            String lastErrorClass,
            Instant sentAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (attempts < 0 || attempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("A delivery has between 0 and " + MAX_ATTEMPTS + " attempts");
        }
        return new Delivery(id, workspaceId, status, attempts, nextAttemptAt, providerUsed, lastErrorClass, sentAt);
    }

    /** Whether REQ-FUNC-058 allows moving from {@code from} to {@code to}. */
    public static boolean isAllowed(DeliveryStatus from, DeliveryStatus to) {
        return ALLOWED.get(Objects.requireNonNull(from, "from")).contains(Objects.requireNonNull(to, "to"));
    }

    /**
     * Records the outcome of one attempt across both providers (REQ-FUNC-055 to 057):
     *
     * <ul>
     *   <li>accepted → {@link DeliveryStatus#SENT};
     *   <li>permanent rejection → {@link DeliveryStatus#DEAD}, with no further attempts;
     *   <li>transient failure → {@link DeliveryStatus#RETRYING} due at {@code retryAt}, or {@link
     *       DeliveryStatus#DEAD} if it was the {@value #MAX_ATTEMPTS}th attempt.
     * </ul>
     *
     * @param provider the provider called last, or {@code "NONE"} when no provider could be called
     * @param retryAt when the next attempt is due; only used after a transient failure
     * @throws IllegalStateTransitionException if the delivery is not PENDING or RETRYING
     */
    public void recordAttempt(ProviderResult result, String provider, Instant now, Instant retryAt) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(now, "now");
        int attempt = attempts + 1;
        DeliveryStatus target = switch (result) {
            case ACCEPTED -> DeliveryStatus.SENT;
            case PERMANENT_REJECTION -> DeliveryStatus.DEAD;
            case TRANSIENT_FAILURE -> attempt >= MAX_ATTEMPTS ? DeliveryStatus.DEAD : DeliveryStatus.RETRYING;
        };
        requireAllowed(target);
        if (target == DeliveryStatus.RETRYING) {
            Objects.requireNonNull(retryAt, "retryAt");
            if (retryAt.isBefore(now)) {
                throw new IllegalArgumentException("The next attempt cannot be due before the failed one");
            }
            nextAttemptAt = micros(retryAt);
        }
        status = target;
        attempts = attempt;
        providerUsed = provider;
        if (result == ProviderResult.ACCEPTED) {
            lastErrorClass = null;
            sentAt = micros(now);
        } else {
            lastErrorClass = result.name();
        }
    }

    /**
     * Cancels an unsent delivery whose invitation or workspace is gone (REQ-FUNC-062).
     *
     * @throws IllegalStateTransitionException if it is SENT or already CANCELLED
     */
    public void cancel() {
        requireAllowed(DeliveryStatus.CANCELLED);
        status = DeliveryStatus.CANCELLED;
    }

    /**
     * Gives up on a delivery that cannot be composed, such as one whose link has expired, without
     * calling any provider. The attempts are not counted, since no attempt was made.
     *
     * @param errorClass the cause, for example {@code LINK_EXPIRED}
     * @throws IllegalStateTransitionException if the delivery is not PENDING or RETRYING
     */
    public void abandon(String errorClass) {
        Objects.requireNonNull(errorClass, "errorClass");
        requireAllowed(DeliveryStatus.DEAD);
        status = DeliveryStatus.DEAD;
        lastErrorClass = errorClass;
    }

    /**
     * Returns a dead workspace-scope delivery to PENDING with its attempts reset, due at {@code now}
     * (REQ-FUNC-060). The last provider and error stay until the next attempt.
     *
     * @throws IllegalStateTransitionException if it is not DEAD
     * @throws IllegalStateException if it is a system-scope delivery
     */
    public void requeue(Instant now) {
        Objects.requireNonNull(now, "now");
        requireAllowed(DeliveryStatus.PENDING);
        if (workspaceId == null) {
            throw new IllegalStateException("Only a workspace-scope delivery can be retried");
        }
        status = DeliveryStatus.PENDING;
        attempts = 0;
        nextAttemptAt = micros(now);
    }

    /**
     * Whether the sealed link secret must be kept: only while the delivery may still be sent. It is
     * discarded once SENT or CANCELLED (REQ-SEC-005).
     */
    public boolean needsSecret() {
        return status != DeliveryStatus.SENT && status != DeliveryStatus.CANCELLED;
    }

    private void requireAllowed(DeliveryStatus target) {
        if (!isAllowed(status, target)) {
            throw new IllegalStateTransitionException(status, target);
        }
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static Map<DeliveryStatus, Set<DeliveryStatus>> allowedTransitions() {
        Map<DeliveryStatus, Set<DeliveryStatus>> allowed = new EnumMap<>(DeliveryStatus.class);
        allowed.put(
                DeliveryStatus.PENDING,
                EnumSet.of(
                        DeliveryStatus.SENT, DeliveryStatus.RETRYING, DeliveryStatus.DEAD, DeliveryStatus.CANCELLED));
        allowed.put(
                DeliveryStatus.RETRYING,
                EnumSet.of(
                        DeliveryStatus.SENT, DeliveryStatus.RETRYING, DeliveryStatus.DEAD, DeliveryStatus.CANCELLED));
        allowed.put(DeliveryStatus.DEAD, EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.CANCELLED));
        allowed.put(DeliveryStatus.SENT, EnumSet.noneOf(DeliveryStatus.class));
        allowed.put(DeliveryStatus.CANCELLED, EnumSet.noneOf(DeliveryStatus.class));
        allowed.replaceAll((from, targets) -> Set.copyOf(targets));
        return Map.copyOf(allowed);
    }

    public UUID id() {
        return id;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public DeliveryStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String providerUsed() {
        return providerUsed;
    }

    public String lastErrorClass() {
        return lastErrorClass;
    }

    public Instant sentAt() {
        return sentAt;
    }

    @Override
    public String toString() {
        return "Delivery[id=" + id + ", status=" + status + ", attempts=" + attempts + "]";
    }
}
