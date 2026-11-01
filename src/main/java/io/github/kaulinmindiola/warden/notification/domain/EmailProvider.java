package io.github.kaulinmindiola.warden.notification.domain;

/** An e-mail provider (SDD V09 port). Implementations never throw: every failure is a result. */
public interface EmailProvider {

    /** A short name for metrics, logs and the delivery record, such as {@code A}. */
    String name();

    /** Sends one message and classifies the answer (REQ-INT-008). */
    ProviderResult send(EmailMessage message);
}
