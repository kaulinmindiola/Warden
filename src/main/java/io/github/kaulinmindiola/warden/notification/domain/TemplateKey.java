package io.github.kaulinmindiola.warden.notification.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The notification templates of release 1.0 and the variables each one may use (SRS appendix B).
 * The new-device notice ({@code security.new-device}) is deferred to release 1.1 with its event.
 *
 * <p>The declared variables are the whole vocabulary of a template: validation rejects any other
 * name (REQ-SEC-013), and rendering needs a value for each name a template uses.
 */
public enum TemplateKey {
    SECURITY_REFRESH_REUSE(
            "security.refresh-reuse", NotificationCategory.SECURITY, NotificationScope.SYSTEM, Set.of("occurredAt")),
    ACCOUNT_PASSWORD_RESET(
            "account.password-reset",
            NotificationCategory.SECURITY,
            NotificationScope.SYSTEM,
            Set.of("resetLink", "expiresAt")),
    ACCOUNT_EMAIL_VERIFICATION(
            "account.email-verification",
            NotificationCategory.SECURITY,
            NotificationScope.SYSTEM,
            Set.of("verificationLink", "expiresAt")),
    ACCOUNT_REGISTRATION_ATTEMPT(
            "account.registration-attempt",
            NotificationCategory.SECURITY,
            NotificationScope.SYSTEM,
            Set.of("occurredAt")),
    ACCOUNT_PASSWORD_CHANGED(
            "account.password-changed", NotificationCategory.SECURITY, NotificationScope.SYSTEM, Set.of("occurredAt")),
    WORKSPACE_INVITATION(
            "workspace.invitation",
            NotificationCategory.TRANSACTIONAL,
            NotificationScope.WORKSPACE,
            Set.of("workspaceName", "role", "invitationLink", "expiresAt")),
    WORKSPACE_ROLE_CHANGED(
            "workspace.role-changed",
            NotificationCategory.INFORMATIONAL,
            NotificationScope.WORKSPACE,
            Set.of("workspaceName", "affectedMember", "previousRole", "newRole"));

    private final String key;
    private final NotificationCategory category;
    private final NotificationScope scope;
    private final Set<String> variables;

    TemplateKey(String key, NotificationCategory category, NotificationScope scope, Set<String> variables) {
        this.key = key;
        this.category = category;
        this.scope = scope;
        this.variables = variables;
    }

    /** The key as stored and exposed, for example {@code workspace.invitation}. */
    public String key() {
        return key;
    }

    public NotificationCategory category() {
        return category;
    }

    public NotificationScope scope() {
        return scope;
    }

    /** The only variable names a template of this key may reference. */
    public Set<String> variables() {
        return variables;
    }

    /** The template key with this stored name, if release 1.0 has one. */
    public static Optional<TemplateKey> fromKey(String key) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.key.equals(key))
                .findFirst();
    }
}
