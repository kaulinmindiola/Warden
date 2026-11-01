package io.github.kaulinmindiola.warden.notification.domain;

import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What the delivery worker needs to compose a delivery it has just leased (SDD V14-A5). The state
 * is not part of it: the result is recorded against the state re-read under lock.
 *
 * @param templateKey the stored template key, for example {@code workspace.invitation}
 * @param locale the locale of the pinned template
 * @param templateVersion the pinned template version (REQ-FUNC-053)
 * @param variables the template values known at creation
 * @param secret the sealed link token, or {@code null} when there is none or it was discarded
 * @param originTraceId the trace of the request that produced the delivery's event (REQ-OBS-006)
 */
public record ClaimedDelivery(
        UUID id,
        String templateKey,
        String locale,
        int templateVersion,
        String recipientEmail,
        Map<String, String> variables,
        SealedSecret secret,
        String originTraceId) {

    public ClaimedDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(templateKey, "templateKey");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(recipientEmail, "recipientEmail");
        Objects.requireNonNull(originTraceId, "originTraceId");
        variables = Map.copyOf(variables);
    }

    /** The address, the values and the secret are left out of logs. */
    @Override
    public String toString() {
        return "ClaimedDelivery[id=" + id + ", templateKey=" + templateKey + ", content redacted]";
    }
}
