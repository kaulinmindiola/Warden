package io.github.kaulinmindiola.warden.shared.audit;

/**
 * Actions recorded in the audit log, as listed by REQ-SEC-015. The name is stored in {@code
 * audit_log.action}; renaming a constant breaks existing records, so constants are only added.
 */
public enum AuditAction {
    /** Login attempt; the outcome distinguishes success from failure. */
    LOGIN,
    /** A refresh token was presented again after being used. */
    REFRESH_TOKEN_REUSE_DETECTED,
    /** The user logged out of the current session. */
    LOGOUT,
    /** The user terminated one of their sessions or all of them. */
    SESSION_TERMINATED,
    /** The password was reset with a one-time token. */
    PASSWORD_RESET,
    /** A workspace was created. */
    WORKSPACE_CREATED,
    /** The role of a member changed. */
    MEMBER_ROLE_CHANGED,
    /** An administrator removed a member. */
    MEMBER_REMOVED,
    /** A member left the workspace. */
    MEMBER_LEFT,
    /** An invitation was created. */
    INVITATION_CREATED,
    /** An invitation was revoked. */
    INVITATION_REVOKED,
    /** An invitation was accepted. */
    INVITATION_ACCEPTED,
    /** A workspace was deleted. */
    WORKSPACE_DELETED,
    /** A notification template was changed. */
    TEMPLATE_CHANGED,
    /** A dead delivery was retried from the dead-letter queue. */
    DELIVERY_RETRIED
}
