package io.github.kaulinmindiola.warden.notification.domain;

/**
 * Life cycle of a delivery (SDD V15): created {@link #PENDING}; an attempt ends in {@link #SENT},
 * {@link #RETRYING} or {@link #DEAD}; an obsolete delivery becomes {@link #CANCELLED}. SENT, DEAD
 * and CANCELLED are terminal, except that a DEAD delivery can be retried by hand.
 */
public enum DeliveryStatus {
    PENDING,
    RETRYING,
    SENT,
    DEAD,
    CANCELLED
}
