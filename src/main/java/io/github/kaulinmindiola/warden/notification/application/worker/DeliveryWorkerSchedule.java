package io.github.kaulinmindiola.warden.notification.application.worker;

import io.github.kaulinmindiola.warden.shared.time.ConditionalOnSchedulingEnabled;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ticks the {@link DeliveryWorker} every second while workers are switched on (SDD V13). */
@Component
@ConditionalOnSchedulingEnabled
class DeliveryWorkerSchedule {

    static final long INTERVAL_MILLIS = 1000;

    private final DeliveryWorker worker;

    DeliveryWorkerSchedule(DeliveryWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelay = INTERVAL_MILLIS)
    void tick() {
        worker.scheduledRun();
    }
}
