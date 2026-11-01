package io.github.kaulinmindiola.warden.notification.domain;

/** How an e-mail provider answered one send (REQ-INT-008, SDD V09). */
public enum ProviderResult {
    /** The provider took the message. */
    ACCEPTED,
    /** The provider will never take it, e.g. an invalid recipient: no retry and no failover. */
    PERMANENT_REJECTION,
    /** Anything else, a timeout or an unreachable provider included: worth another try. */
    TRANSIENT_FAILURE
}
