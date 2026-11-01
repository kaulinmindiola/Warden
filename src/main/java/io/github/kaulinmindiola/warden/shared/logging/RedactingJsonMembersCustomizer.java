package io.github.kaulinmindiola.warden.shared.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applies {@link LogRedactor} to every text value of a structured log line: the message, the MDC
 * fields and the stack trace (REQ-OBS-005). Registered through {@code
 * logging.structured.json.customizer}, so it is created by the logging system before the
 * application context and has no dependencies.
 */
public class RedactingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogRedactor::redact));
    }
}
