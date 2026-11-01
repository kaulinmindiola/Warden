package io.github.kaulinmindiola.warden.notification.application.worker;

import io.github.kaulinmindiola.warden.shared.time.ConditionalOnSchedulingEnabled;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ticks the {@link OutboxDispatchWorker} every 500 ms while workers are switched on (SDD V13). */
@Component
@ConditionalOnSchedulingEnabled
class OutboxDispatchSchedule {

    static final long INTERVAL_MILLIS = 500;

    private final OutboxDispatchWorker worker;

    OutboxDispatchSchedule(OutboxDispatchWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelay = INTERVAL_MILLIS)
    void tick() {
        worker.scheduledRun();
    }
}
