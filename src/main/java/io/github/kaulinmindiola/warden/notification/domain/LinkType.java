package io.github.kaulinmindiola.warden.notification.domain;

import java.util.Optional;

/**
 * The single-use links a notification can carry (REQ-INT-009), each with the template variable
 * that receives it.
 */
public enum LinkType {
    EMAIL_VERIFICATION("verificationLink"),
    PASSWORD_RESET("resetLink"),
    INVITATION("invitationLink");

    private final String variable;

    LinkType(String variable) {
        this.variable = variable;
    }

    /** The template variable the link is rendered into. */
    public String variable() {
        return variable;
    }

    /** The link a template of this key carries, if it carries one. */
    public static Optional<LinkType> of(TemplateKey key) {
        return switch (key) {
            case ACCOUNT_EMAIL_VERIFICATION -> Optional.of(EMAIL_VERIFICATION);
            case ACCOUNT_PASSWORD_RESET -> Optional.of(PASSWORD_RESET);
            case WORKSPACE_INVITATION -> Optional.of(INVITATION);
            case SECURITY_REFRESH_REUSE,
                    ACCOUNT_REGISTRATION_ATTEMPT,
                    ACCOUNT_PASSWORD_CHANGED,
                    WORKSPACE_ROLE_CHANGED -> Optional.empty();
        };
    }
}
