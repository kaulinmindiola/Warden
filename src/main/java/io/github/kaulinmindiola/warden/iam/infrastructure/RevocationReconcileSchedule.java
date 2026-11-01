package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.shared.time.ConditionalOnSchedulingEnabled;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ticks the {@link RevocationReconciler} every 5 seconds while workers are switched on (ADR-007). */
@Component
@ConditionalOnSchedulingEnabled
class RevocationReconcileSchedule {

    private final RevocationReconciler reconciler;

    RevocationReconcileSchedule(RevocationReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelay = 5, initialDelay = 5, timeUnit = TimeUnit.SECONDS)
    void tick() {
        reconciler.tick();
    }
}
