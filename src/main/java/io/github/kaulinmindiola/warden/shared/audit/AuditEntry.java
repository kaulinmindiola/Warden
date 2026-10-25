package io.github.kaulinmindiola.warden.shared.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What happened, by whom and where, as recorded by {@link AuditLog}. The time, the outcome and
 * the trace id are added when the entry is recorded.
 *
 * @param action audited action
 * @param actorUserId user who performed the action, or {@code null} when unknown (for example a
 *     failed login for an e-mail that has no account)
 * @param workspaceId workspace the action applies to, or {@code null} for account-level actions
 * @param details extra non-secret context, such as a reason or a new role
 */
public record AuditEntry(AuditAction action, UUID actorUserId, UUID workspaceId, Map<String, String> details) {

    /** Values that look like Warden tokens or a JWT; audit records must never contain secrets. */
    private static final Pattern TOKEN_LIKE = Pattern.compile("wrt_|wot_|eyJ");

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        details = details == null ? Map.of() : Map.copyOf(details);
        for (Map.Entry<String, String> detail : details.entrySet()) {
            if (TOKEN_LIKE.matcher(detail.getValue()).find()) {
                throw new IllegalArgumentException("Audit detail '" + detail.getKey()
                        + "' looks like a token; audit records must not contain secrets");
            }
        }
    }

    /** Entry without details. */
    public static AuditEntry of(AuditAction action, UUID actorUserId, UUID workspaceId) {
        return new AuditEntry(action, actorUserId, workspaceId, Map.of());
    }
}
