package io.github.kaulinmindiola.warden.notification.domain;

import java.util.Objects;

/**
 * A template with its placeholders replaced. It may contain a link token, so its {@link #toString}
 * leaves the text out and it is never logged or returned by the API (REQ-SEC-014).
 */
public record RenderedMessage(String subject, String body) {

    public RenderedMessage {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
    }

    @Override
    public String toString() {
        return "RenderedMessage[content redacted]";
    }
}
