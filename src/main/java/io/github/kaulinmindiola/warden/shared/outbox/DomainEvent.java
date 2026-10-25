package io.github.kaulinmindiola.warden.shared.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Envelope of an event stored in {@code outbox_event} (SDD V10, REQ-MAINT-005).
 *
 * @param eventId idempotency key for consumers, a UUIDv7
 * @param type {@code module.EventName}, for example {@code iam.EmailVerificationRequested}
 * @param schemaVersion version of the payload schema, starting at 1
 * @param occurredAt business time from the application clock, in microseconds
 * @param workspaceId workspace the event belongs to, or {@code null} for system scope
 * @param traceId trace of the request that produced the event (REQ-OBS-006)
 * @param payload type-specific data as a JSON object
 */
public record DomainEvent(
        UUID eventId,
        String type,
        int schemaVersion,
        Instant occurredAt,
        UUID workspaceId,
        String traceId,
        String payload) {

    private static final Pattern TYPE = Pattern.compile("[a-z]+\\.[A-Z][A-Za-z0-9]*");

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(payload, "payload");
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Event type must have the form module.EventName: " + type);
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("Schema version must be at least 1");
        }
        if (!payload.startsWith("{")) {
            throw new IllegalArgumentException("Event payload must be a JSON object");
        }
    }

    /** The payload is left out: it may contain e-mail addresses and sealed secrets. */
    @Override
    public String toString() {
        return "DomainEvent[eventId=" + eventId + ", type=" + type + ", schemaVersion=" + schemaVersion
                + ", occurredAt=" + occurredAt + ", workspaceId=" + workspaceId + ", traceId=" + traceId
                + ", payload redacted]";
    }
}
