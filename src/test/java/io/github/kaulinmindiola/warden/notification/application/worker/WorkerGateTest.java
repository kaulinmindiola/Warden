package io.github.kaulinmindiola.warden.notification.application.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

/** The gate that stops the claiming workers when the application shuts down (REQ-AVAIL-002). */
class WorkerGateTest {

    private final WorkerGate gate = new WorkerGate();
    private final AtomicInteger stopped = new AtomicInteger();

    @Test
    void untilTheApplicationStartsNoRunIsAdmitted() {
        assertThat(gate.isRunning()).isFalse();
        assertThat(gate.tryEnter()).isFalse();
    }

    @Test
    void whileOpenEveryRunIsAdmittedAndCounted() {
        gate.start();

        assertThat(gate.tryEnter()).isTrue();
        assertThat(gate.tryEnter()).isTrue();
        gate.exit();

        assertThat(gate.isRunning()).isTrue();
        assertThat(gate.active()).isEqualTo(1);
    }

    @Test
    @Verifies("REQ-AVAIL-002")
    void anIdleGateClosesAndReportsTheStopAtOnce() {
        gate.start();

        gate.stop(stopped::incrementAndGet);

        assertThat(stopped).hasValue(1);
        assertThat(gate.isRunning()).isFalse();
        assertThat(gate.tryEnter()).isFalse();
    }

    @Test
    @Verifies("REQ-AVAIL-002")
    void aBusyGateAdmitsNoNewRunAndReportsTheStopWhenTheLastRunEnds() {
        gate.start();
        gate.tryEnter();
        gate.tryEnter();

        gate.stop(stopped::incrementAndGet);
        assertThat(gate.tryEnter()).as("a new run while shutting down").isFalse();
        gate.exit();
        assertThat(stopped).as("one run still in progress").hasValue(0);
        gate.exit();

        assertThat(stopped).hasValue(1);
        assertThat(gate.active()).isZero();
    }

    @Test
    void itStopsInThePhaseOfTheWebServersGracefulShutdown() {
        assertThat(gate.getPhase()).isEqualTo(SmartLifecycle.DEFAULT_PHASE - 1024);
        assertThat(gate.isAutoStartup()).isTrue();
    }
}
