package io.github.kaulinmindiola.warden.shared.audit;

import io.github.kaulinmindiola.warden.shared.time.ConditionalOnSchedulingEnabled;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link AuditRetention} purge every day at 03:17 UTC, outside the hours of most traffic,
 * while workers are switched on (REQ-SEC-016).
 */
@Component
@ConditionalOnSchedulingEnabled
class AuditPurgeSchedule {

    private final AuditRetention retention;

    AuditPurgeSchedule(AuditRetention retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
    void run() {
        retention.scheduledPurge();
    }
}
