package io.github.kaulinmindiola.warden.workspace.domain;

/**
 * Stored states of an invitation (SDD V15). {@code EXPIRED} is not stored: a {@link #PENDING}
 * invitation whose expiry has passed is expired.
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED,
    REPLACED
}
