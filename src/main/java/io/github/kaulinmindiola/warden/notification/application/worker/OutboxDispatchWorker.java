package io.github.kaulinmindiola.warden.notification.application.worker;

import io.github.kaulinmindiola.warden.shared.outbox.OutboxDispatcher;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drives the outbox dispatcher (SDD V13); {@link OutboxDispatchSchedule} ticks it every 500 ms
 * while workers are switched on. Each tick dispatches one batch in a new system-context
 * transaction, which the notification handler needs to create deliveries for any workspace (SDD
 * V07). Only this package may open such transactions (SDD V04 R5, FF-03), which is why the tick
 * lives here and not next to the dispatcher in {@code shared}.
 *
 * <p>Every instance runs the tick; {@code FOR UPDATE SKIP LOCKED} keeps their batches disjoint.
 */
@Component
public class OutboxDispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchWorker.class);

    private final TenantTransactions transactions;
    private final OutboxDispatcher dispatcher;

    public OutboxDispatchWorker(TenantTransactions transactions, OutboxDispatcher dispatcher) {
        this.transactions = transactions;
        this.dispatcher = dispatcher;
    }

    /**
     * One scheduled tick. A failed tick, for instance while PostgreSQL is unavailable, is logged and
     * the next one tries again; the batch it held stays pending.
     */
    public void scheduledRun() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.warn(
                    "Outbox dispatch failed ({}); retrying on the next tick",
                    failure.getClass().getName());
        }
    }

    /**
     * Dispatches one batch now. Tests call it directly, since workers are switched off on the
     * test classpath.
     *
     * @return the number of events marked published
     */
    public int runOnce() {
        return transactions.system(dispatcher::dispatchBatch);
    }
}
