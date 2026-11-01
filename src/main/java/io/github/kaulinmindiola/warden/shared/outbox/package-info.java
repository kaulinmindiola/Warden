/**
 * Transactional outbox (REQ-REL-001, ADR-008).
 *
 * <p>Modules publish domain events through {@link
 * io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher}, which requires the caller's
 * transaction: the event is stored atomically with the state change and is never published for
 * a change that rolled back.
 *
 * <p>{@link io.github.kaulinmindiola.warden.shared.outbox.OutboxDispatcher} hands stored events
 * to every {@link io.github.kaulinmindiola.warden.shared.outbox.OutboxEventHandler} in batches
 * locked with {@code FOR UPDATE SKIP LOCKED}, marks them published and strips their sealed secret.
 * It runs in a transaction opened by the notification worker.
 *
 * <p>Exposed to the other modules as the {@code outbox} named interface.
 */
@NamedInterface("outbox")
package io.github.kaulinmindiola.warden.shared.outbox;

import org.springframework.modulith.NamedInterface;
