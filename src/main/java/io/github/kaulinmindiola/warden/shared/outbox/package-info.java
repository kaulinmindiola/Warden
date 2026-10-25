/**
 * Transactional outbox (REQ-REL-001, ADR-008).
 *
 * <p>Modules publish domain events through {@link
 * io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher}, which requires the caller's
 * transaction: the event is stored atomically with the state change and is never published for
 * a change that rolled back. The dispatcher that delivers stored events to consumers arrives in
 * phase 4.
 *
 * <p>Exposed to the other modules as the {@code outbox} named interface.
 */
@NamedInterface("outbox")
package io.github.kaulinmindiola.warden.shared.outbox;

import org.springframework.modulith.NamedInterface;
