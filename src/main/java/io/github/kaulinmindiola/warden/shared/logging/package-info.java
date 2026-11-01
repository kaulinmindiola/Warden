/**
 * Logging conventions (REQ-OBS-004, REQ-OBS-005, REQ-OBS-006, SDD V19): the MDC field names that
 * correlate log lines, and the redaction applied to every structured log line.
 *
 * <p>Exposed to the other modules as the {@code logging} named interface.
 */
@NamedInterface("logging")
package io.github.kaulinmindiola.warden.shared.logging;

import org.springframework.modulith.NamedInterface;
