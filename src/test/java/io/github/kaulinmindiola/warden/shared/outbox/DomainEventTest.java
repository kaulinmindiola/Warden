package io.github.kaulinmindiola.warden.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainEventTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsAValidEnvelope() {
        DomainEvent event = new DomainEvent(ID, "iam.EmailVerificationRequested", 1, NOW, null, TRACE, "{}");

        assertThat(event.type()).isEqualTo("iam.EmailVerificationRequested");
        assertThat(event.workspaceId()).isNull();
    }

    @Test
    void rejectsTypesThatAreNotModuleDotEventName() {
        for (String type : new String[] {"EmailVerificationRequested", "iam.emailVerified", "IAM.Event", "iam."}) {
            assertThatThrownBy(() -> new DomainEvent(ID, type, 1, NOW, null, TRACE, "{}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Event type must have the form module.EventName");
        }
    }

    @Test
    void rejectsASchemaVersionBelowOneAndANonObjectPayload() {
        assertThatThrownBy(() -> new DomainEvent(ID, "iam.Event", 0, NOW, null, TRACE, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Schema version must be at least 1");
        assertThatThrownBy(() -> new DomainEvent(ID, "iam.Event", 1, NOW, null, TRACE, "\"text\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event payload must be a JSON object");
    }

    @Test
    void theTextualFormLeavesThePayloadOut() {
        DomainEvent event = new DomainEvent(ID, "iam.Event", 1, NOW, null, TRACE, "{\"email\":\"ana@mail.com\"}");

        assertThat(event.toString()).contains("iam.Event", "payload redacted").doesNotContain("ana@mail.com");
    }
}
