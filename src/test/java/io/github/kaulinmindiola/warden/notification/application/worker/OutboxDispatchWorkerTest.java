package io.github.kaulinmindiola.warden.notification.application.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.kaulinmindiola.warden.shared.outbox.OutboxDispatcher;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** The tick that drives the outbox dispatcher. */
class OutboxDispatchWorkerTest {

    private final TenantTransactions transactions = mock(TenantTransactions.class);
    private final OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
    private final WorkerGate gate = new WorkerGate();
    private final OutboxDispatchWorker worker = new OutboxDispatchWorker(transactions, dispatcher, gate);

    @BeforeEach
    void openTheGate() {
        gate.start();
    }

    @Test
    void eachRunDispatchesOneBatchInASystemContextTransaction() {
        when(transactions.system(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        when(dispatcher.dispatchBatch()).thenReturn(3);

        assertThat(worker.runOnce()).isEqualTo(3);
        verify(transactions).system(any());
        verify(dispatcher).dispatchBatch();
    }

    @Test
    void onceTheGateIsClosedNoBatchIsTaken() {
        gate.stop();

        assertThat(worker.runOnce()).isZero();
        verifyNoInteractions(transactions, dispatcher);
    }

    @Test
    void aFailedTickIsLoggedAndDoesNotStopTheSchedule() {
        when(transactions.system(any())).thenThrow(new CannotGetJdbcConnectionException("Connection is not available"));

        assertThatCode(worker::scheduledRun).doesNotThrowAnyException();
    }
}
