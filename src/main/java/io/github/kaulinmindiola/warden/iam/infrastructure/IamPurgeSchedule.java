package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.shared.time.ConditionalOnSchedulingEnabled;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link IamRetention} purge every day at 03:37 UTC, after the audit and outbox purges,
 * while workers are switched on (SDD V06).
 */
@Component
@ConditionalOnSchedulingEnabled
class IamPurgeSchedule {

    private final IamRetention retention;

    IamPurgeSchedule(IamRetention retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "0 37 3 * * *", zone = "UTC")
    void run() {
        retention.scheduledPurge();
    }
}
