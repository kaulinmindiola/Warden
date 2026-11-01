package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus;
import io.github.kaulinmindiola.warden.notification.domain.NewDelivery;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
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
}
