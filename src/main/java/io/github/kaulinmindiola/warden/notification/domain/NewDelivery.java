package io.github.kaulinmindiola.warden.notification.domain;

import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A delivery about to be created by the fan-out (SDD V13). It is created {@link
 * DeliveryStatus#PENDING}, due immediately, with the template version it will be rendered with.
 *
 * @param eventId the event it comes from; with the recipient key and the channel, its identity
 * @param recipientKey the user id, or {@code "email:" + hmac(email)} for an address without account
 * @param workspaceId the workspace of a workspace-scope notification, {@code null} otherwise
 * @param invitationId the invitation a {@code workspace.invitation} delivery belongs to
 * @param locale the locale of the pinned template, which may be the {@code es} fallback
 * @param variables the values of the template variables known at creation; links are built from
 *     the secret when the delivery is sent
 * @param secret the sealed link token, or {@code null} for notifications without link
 * @param originTraceId the trace of the request that produced the event (REQ-OBS-006)
 */
public record NewDelivery(
        UUID eventId,
        String recipientKey,
        TemplateKey templateKey,
        UUID workspaceId,
        UUID invitationId,
        String locale,
        int templateVersion,
        String recipientEmail,
        Map<String, String> variables,
        SealedSecret secret,
        String originTraceId) {

    /** The only channel of release 1.0. */
    public static final String CHANNEL = "EMAIL";

    public NewDelivery {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(recipientKey, "recipientKey");
        Objects.requireNonNull(templateKey, "templateKey");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(recipientEmail, "recipientEmail");
        Objects.requireNonNull(originTraceId, "originTraceId");
        variables = Map.copyOf(variables);
        if ((templateKey.scope() == NotificationScope.WORKSPACE) != (workspaceId != null)) {
            throw new IllegalArgumentException(
                    "A workspace-scope delivery needs a workspace and a system-scope delivery has none");
        }
    }

    /** The e-mail address and the secret are left out of logs. */
    @Override
    public String toString() {
        return "NewDelivery[eventId=" + eventId + ", templateKey=" + templateKey.key() + ", locale=" + locale
                + ", templateVersion=" + templateVersion + ", recipient redacted]";
    }
}
