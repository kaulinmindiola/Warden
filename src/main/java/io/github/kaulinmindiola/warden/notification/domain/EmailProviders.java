package io.github.kaulinmindiola.warden.notification.domain;

import java.util.Objects;

/**
 * The two providers in the order of an attempt: the primary, chosen by configuration, and the
 * secondary it fails over to (REQ-INT-008, REQ-FUNC-055).
 */
public record EmailProviders(EmailProvider primary, EmailProvider secondary) {

    public EmailProviders {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(secondary, "secondary");
        if (primary.name().equals(secondary.name())) {
            throw new IllegalArgumentException("The primary and the secondary provider must differ");
        }
    }
}
