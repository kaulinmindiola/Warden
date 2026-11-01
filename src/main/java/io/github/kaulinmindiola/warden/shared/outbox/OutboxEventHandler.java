package io.github.kaulinmindiola.warden.shared.outbox;

/**
 * Consumer of the events stored in the outbox (SDD V13, ADR-008). Every handler bean receives
 * every dispatched event and ignores the types it does not consume.
 *
 * <p>The contract follows from running inside the dispatch transaction:
 *
 * <ul>
 *   <li>The handler writes in the caller's transaction, which already carries the system context.
 *       Its writes commit together with the event being marked published, or not at all.
 *   <li>It must be idempotent: after a crash before that commit the same event is handed over
 *       again, in this or another instance.
 *   <li>It writes through {@code JdbcClient}. A failure rolls the JDBC writes of that event back to
 *       a savepoint; JPA entities it changed would not be rolled back.
 *   <li>It does not open transactions of its own. An exception escaping from a transactional bean
 *       it calls marks the whole dispatch transaction for rollback, so the whole batch is retried.
 * </ul>
 *
 * <p>A handler that throws leaves the event pending: the dispatcher retries it on the next tick
 * and goes on with the rest of the batch.
 */
@FunctionalInterface
public interface OutboxEventHandler {

    /**
     * Consumes one event.
     *
     * @param event the stored envelope, with its payload as a JSON object, secret included
     */
    void handle(DomainEvent event);
}
