package io.github.kaulinmindiola.warden.shared.outbox;

import io.github.kaulinmindiola.warden.shared.time.ConditionalOnSchedulingEnabled;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link OutboxRetention} purge every day at 03:27 UTC, after the audit purge, while
 * workers are switched on (REQ-FUNC-063).
 */
@Component
@ConditionalOnSchedulingEnabled
class OutboxPurgeSchedule {

    private final OutboxRetention retention;

    OutboxPurgeSchedule(OutboxRetention retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "0 27 3 * * *", zone = "UTC")
    void run() {
        retention.scheduledPurge();
    }
}
