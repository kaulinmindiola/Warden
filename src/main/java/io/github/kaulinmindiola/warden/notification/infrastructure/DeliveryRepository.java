package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.kaulinmindiola.warden.notification.domain.ClaimedDelivery;
import io.github.kaulinmindiola.warden.notification.domain.Delivery;
import io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus;
import io.github.kaulinmindiola.warden.notification.domain.DeliveryView;
import io.github.kaulinmindiola.warden.notification.domain.NewDelivery;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code notification_delivery}. Rows are visible only in the system context or in their
 * workspace's context (Row Level Security), so callers run in one of them.
 */
@Repository
public class DeliveryRepository {

    private static final String INSERT = """
            INSERT INTO notification_delivery (
                id, event_id, recipient_key, channel, scope, workspace_id, invitation_id,
                template_key, locale, template_version, recipient_email, variables,
                secret_ciphertext, secret_key_id, secret_expires_at,
                status, attempts, next_attempt_at, origin_trace_id, created_at)
            VALUES (
                :id, :eventId, :recipientKey, :channel, :scope, :workspaceId, :invitationId,
                :templateKey, :locale, :templateVersion, :recipientEmail, CAST(:variables AS jsonb),
                :secretCiphertext, :secretKeyId, :secretExpiresAt,
                :status, 0, :now, :originTraceId, :now)
            ON CONFLICT (event_id, recipient_key, channel) DO NOTHING
            """;

    /**
     * Deliveries not sent yet, including DEAD ones that could be retried, become CANCELLED and lose
     * their sealed token (REQ-FUNC-062, REQ-SEC-005). A delivery in flight keeps its lease; its
     * worker sees CANCELLED when it records the result and keeps it (SDD V13).
     */
    private static final String CANCEL_UNSENT = """
            UPDATE notification_delivery
            SET status = 'CANCELLED', secret_ciphertext = NULL, secret_key_id = NULL
            WHERE status IN ('PENDING', 'RETRYING', 'DEAD') AND %s = :id
            """;

    /**
     * Leases up to {@code :limit} due deliveries (SDD V14-A5). {@code SKIP LOCKED} keeps concurrent
     * claims disjoint, and the lease keeps a claimed delivery out of later claims until it lapses.
     */
    private static final String CLAIM_DUE = """
            UPDATE notification_delivery
            SET lease_until = :leaseUntil
            WHERE id IN (
                SELECT id
                FROM notification_delivery
                WHERE status IN ('PENDING', 'RETRYING')
                  AND next_attempt_at <= :now
                  AND (lease_until IS NULL OR lease_until < :now)
                ORDER BY next_attempt_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            RETURNING id, template_key, locale, template_version, recipient_email, variables::text AS variables,
                      secret_ciphertext, secret_key_id, secret_expires_at
            """;

    private static final String LOCK_STATE = """
            SELECT id, workspace_id, status, attempts, next_attempt_at, provider_used, last_error_class, sent_at
            FROM notification_delivery
            WHERE id = :id
            FOR UPDATE
            """;

    /** Releases the lease and drops the sealed token once it is no longer needed (REQ-SEC-005). */
    private static final String SAVE_STATE = """
            UPDATE notification_delivery
            SET status = :status,
                attempts = :attempts,
                next_attempt_at = :nextAttemptAt,
                provider_used = :providerUsed,
                last_error_class = :lastErrorClass,
                sent_at = :sentAt,
                lease_until = NULL,
                secret_ciphertext = CASE WHEN :keepSecret THEN secret_ciphertext END,
                secret_key_id = CASE WHEN :keepSecret THEN secret_key_id END
            WHERE id = :id
            """;

    /** A token that can no longer be opened is useless, whatever the state (REQ-SEC-005). */
    private static final String DISCARD_EXPIRED_SECRETS = """
            UPDATE notification_delivery
            SET secret_ciphertext = NULL, secret_key_id = NULL
            WHERE secret_ciphertext IS NOT NULL AND secret_expires_at <= :now
            """;

    /** Workspace deliveries, newest first, optionally of one state; never their content. */
    private static final String FIND_OF_WORKSPACE = """
            SELECT id, template_key, recipient_email, status, attempts, provider_used, last_error_class,
                   created_at, next_attempt_at, sent_at
            FROM notification_delivery
            WHERE workspace_id = :workspaceId AND (CAST(:status AS text) IS NULL OR status = :status)
            ORDER BY created_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_OF_WORKSPACE = """
            SELECT count(*)
            FROM notification_delivery
            WHERE workspace_id = :workspaceId AND (CAST(:status AS text) IS NULL OR status = :status)
            """;

    /**
     * A workspace delivery locked for a manual retry, with what decides whether its invitation link
     * can still be used: present and not expired.
     */
    private static final String LOCK_FOR_RETRY = """
            SELECT id, workspace_id, status, attempts, next_attempt_at, provider_used, last_error_class, sent_at,
                   invitation_id,
                   secret_ciphertext IS NOT NULL AND secret_expires_at > :now AS link_usable
            FROM notification_delivery
            WHERE id = :id AND workspace_id = :workspaceId
            FOR UPDATE
            """;

    private static final TypeReference<Map<String, String>> VARIABLES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public DeliveryRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Creates the delivery, pending and due now, unless one exists for the same event, recipient
     * and channel (REQ-FUNC-061).
     *
     * @return whether a row was created
     */
    public boolean insertIfAbsent(UUID id, NewDelivery delivery, Instant now) {
        SealedSecret secret = delivery.secret();
        int inserted = jdbc.sql(INSERT)
                .param("id", id)
                .param("eventId", delivery.eventId())
                .param("recipientKey", delivery.recipientKey())
                .param("channel", NewDelivery.CHANNEL)
                .param("scope", delivery.templateKey().scope().name())
                .param("workspaceId", new SqlParameterValue(Types.OTHER, delivery.workspaceId()))
                .param("invitationId", new SqlParameterValue(Types.OTHER, delivery.invitationId()))
                .param("templateKey", delivery.templateKey().key())
                .param("locale", delivery.locale())
                .param("templateVersion", delivery.templateVersion())
                .param("recipientEmail", delivery.recipientEmail())
                .param("variables", jsonMapper.writeValueAsString(delivery.variables()))
                .param(
                        "secretCiphertext",
                        new SqlParameterValue(
                                Types.BINARY,
                                secret == null ? null : Base64.getDecoder().decode(secret.ciphertext())))
                .param("secretKeyId", new SqlParameterValue(Types.VARCHAR, secret == null ? null : secret.keyId()))
                .param(
                        "secretExpiresAt",
                        new SqlParameterValue(
                                Types.TIMESTAMP_WITH_TIMEZONE,
                                secret == null ? null : OffsetDateTime.ofInstant(secret.expiresAt(), ZoneOffset.UTC)))
                .param("status", DeliveryStatus.PENDING.name())
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("originTraceId", delivery.originTraceId())
                .update();
        return inserted == 1;
    }

    /**
     * Leases the due deliveries, oldest due first, until {@code leaseUntil}. Commit right away, so
     * other instances see the lease while the deliveries are being sent.
     */
    public List<ClaimedDelivery> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql(CLAIM_DUE)
                .param("now", utc(now))
                .param("leaseUntil", utc(leaseUntil))
                .param("limit", limit)
                .query(this::toClaimed)
                .list();
    }

    /** The state of a delivery, locked until the end of the transaction. */
    public Optional<Delivery> lockState(UUID id) {
        return jdbc.sql(LOCK_STATE)
                .param("id", id)
                .query(DeliveryRepository::toDelivery)
                .optional();
    }

    /**
     * Stores the state of a delivery and releases its lease. The sealed token is dropped when the
     * delivery no longer needs it.
     */
    public void saveState(Delivery delivery) {
        jdbc.sql(SAVE_STATE)
                .param("id", delivery.id())
                .param("status", delivery.status().name())
                .param("attempts", delivery.attempts())
                .param("nextAttemptAt", utc(delivery.nextAttemptAt()))
                .param("providerUsed", new SqlParameterValue(Types.VARCHAR, delivery.providerUsed()))
                .param("lastErrorClass", new SqlParameterValue(Types.VARCHAR, delivery.lastErrorClass()))
                .param(
                        "sentAt",
                        new SqlParameterValue(
                                Types.TIMESTAMP_WITH_TIMEZONE,
                                delivery.sentAt() == null ? null : utc(delivery.sentAt())))
                .param("keepSecret", delivery.needsSecret())
                .update();
    }

    /**
     * The deliveries of a workspace, newest first, optionally only those in {@code status}
     * (REQ-FUNC-059). System-scope deliveries have no workspace and never appear.
     */
    public Page<DeliveryView> findOfWorkspace(UUID workspaceId, DeliveryStatus status, Pageable pageable) {
        SqlParameterValue statusValue = new SqlParameterValue(Types.VARCHAR, status == null ? null : status.name());
        List<DeliveryView> items = jdbc.sql(FIND_OF_WORKSPACE)
                .param("workspaceId", workspaceId)
                .param("status", statusValue)
                .param("limit", pageable.getPageSize())
                .param("offset", pageable.getOffset())
                .query(DeliveryRepository::toView)
                .list();
        long total = jdbc.sql(COUNT_OF_WORKSPACE)
                .param("workspaceId", workspaceId)
                .param("status", statusValue)
                .query(Long.class)
                .single();
        return new PageImpl<>(items, pageable, total);
    }

    /** A workspace delivery locked until the end of the transaction, for a manual retry. */
    public Optional<RetryCandidate> lockForRetry(UUID workspaceId, UUID id, Instant now) {
        return jdbc.sql(LOCK_FOR_RETRY)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("now", utc(now))
                .query((row, rowNumber) -> new RetryCandidate(
                        toDelivery(row, rowNumber),
                        row.getObject("invitation_id", UUID.class),
                        row.getBoolean("link_usable")))
                .optional();
    }

    /**
     * Drops every sealed token that has expired at {@code now}.
     *
     * @return how many tokens were dropped
     */
    public int discardExpiredSecrets(Instant now) {
        return jdbc.sql(DISCARD_EXPIRED_SECRETS).param("now", utc(now)).update();
    }

    /**
     * Cancels the unsent deliveries of a revoked or replaced invitation.
     *
     * @return how many deliveries were cancelled
     */
    public int cancelUnsentOfInvitation(UUID invitationId) {
        return jdbc.sql(CANCEL_UNSENT.formatted("invitation_id"))
                .param("id", invitationId)
                .update();
    }

    /**
     * Cancels every unsent delivery of a deleted workspace.
     *
     * @return how many deliveries were cancelled
     */
    public int cancelUnsentOfWorkspace(UUID workspaceId) {
        return jdbc.sql(CANCEL_UNSENT.formatted("workspace_id"))
                .param("id", workspaceId)
                .update();
    }

    private ClaimedDelivery toClaimed(ResultSet row, int rowNumber) throws SQLException {
        byte[] ciphertext = row.getBytes("secret_ciphertext");
        SealedSecret secret = ciphertext == null
                ? null
                : new SealedSecret(
                        Base64.getEncoder().encodeToString(ciphertext),
                        row.getString("secret_key_id"),
                        row.getObject("secret_expires_at", OffsetDateTime.class).toInstant());
        return new ClaimedDelivery(
                row.getObject("id", UUID.class),
                row.getString("template_key"),
                row.getString("locale"),
                row.getInt("template_version"),
                row.getString("recipient_email"),
                jsonMapper.readValue(row.getString("variables"), VARIABLES),
                secret);
    }

    private static DeliveryView toView(ResultSet row, int rowNumber) throws SQLException {
        return new DeliveryView(
                row.getObject("id", UUID.class),
                row.getString("template_key"),
                row.getString("recipient_email"),
                DeliveryStatus.valueOf(row.getString("status")),
                row.getInt("attempts"),
                row.getString("provider_used"),
                row.getString("last_error_class"),
                instant(row, "created_at"),
                instant(row, "next_attempt_at"),
                instant(row, "sent_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Delivery toDelivery(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime sentAt = row.getObject("sent_at", OffsetDateTime.class);
        return Delivery.restore(
                row.getObject("id", UUID.class),
                row.getObject("workspace_id", UUID.class),
                DeliveryStatus.valueOf(row.getString("status")),
                row.getInt("attempts"),
                row.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                row.getString("provider_used"),
                row.getString("last_error_class"),
                sentAt == null ? null : sentAt.toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * A delivery about to be retried by hand.
     *
     * @param invitationId the invitation it belongs to, or {@code null}
     * @param linkUsable whether it still holds a sealed token that has not expired
     */
    public record RetryCandidate(Delivery delivery, UUID invitationId, boolean linkUsable) {}
}
