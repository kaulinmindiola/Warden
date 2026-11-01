package io.github.kaulinmindiola.warden.notification.domain;

/**
 * Category of a notification (SRS appendix B). Only {@link #INFORMATIONAL} notifications honour
 * the recipient's preferences; the others are always created (REQ-FUNC-048, 050).
 */
public enum NotificationCategory {
    SECURITY,
    TRANSACTIONAL,
    INFORMATIONAL
}
