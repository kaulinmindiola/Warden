package io.github.kaulinmindiola.warden.shared.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.kaulinmindiola.warden.iam.infrastructure.RevocationReconciler;
import io.github.kaulinmindiola.warden.notification.application.worker.DeliveryWorker;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.shared.audit.AuditRetention;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The switch {@code warden.scheduling.enabled} decides whether the schedule beans of the workers
 * exist: present by default, absent when switched off.
 */
class WorkerSchedulesTest {

    private static final String[] SCHEDULES = {
        "io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchSchedule",
        "io.github.kaulinmindiola.warden.notification.application.worker.DeliveryWorkerSchedule",
        "io.github.kaulinmindiola.warden.iam.infrastructure.RevocationReconcileSchedule",
        "io.github.kaulinmindiola.warden.shared.audit.AuditPurgeSchedule"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(OutboxDispatchWorker.class, () -> mock(OutboxDispatchWorker.class))
            .withBean(DeliveryWorker.class, () -> mock(DeliveryWorker.class))
            .withBean(RevocationReconciler.class, () -> mock(RevocationReconciler.class))
            .withBean(AuditRetention.class, () -> mock(AuditRetention.class))
            .withUserConfiguration(scheduleClasses());

    @Test
    void everyWorkerIsScheduledUnlessSwitchedOff() {
        runner.run(context -> {
            for (Class<?> schedule : scheduleClasses()) {
                assertThat(context).hasSingleBean(schedule);
            }
        });
    }

    @Test
    void switchingWorkersOffRemovesEverySchedule() {
        runner.withPropertyValues("warden.scheduling.enabled=false").run(context -> {
            for (Class<?> schedule : scheduleClasses()) {
                assertThat(context).doesNotHaveBean(schedule);
            }
        });
    }

    /** The schedules are package-private in their modules, so they are loaded by name. */
    private static Class<?>[] scheduleClasses() {
        Class<?>[] classes = new Class<?>[SCHEDULES.length];
        for (int i = 0; i < SCHEDULES.length; i++) {
            try {
                classes[i] = Class.forName(SCHEDULES[i]);
            } catch (ClassNotFoundException missing) {
                throw new IllegalStateException("Schedule class not found: " + SCHEDULES[i], missing);
            }
        }
        return classes;
    }
}
