package io.github.kaulinmindiola.warden.notification.application.worker;

import io.github.kaulinmindiola.warden.shared.outbox.OutboxMetrics;
import io.github.kaulinmindiola.warden.shared.time.ConditionalOnSchedulingEnabled;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the gauges that count rows, deliveries by status and scope and pending outbox events,
 * every 10 s while workers are switched on (REQ-OBS-002), so a scrape shows counts at most 10 s
 * old. Each refresh logs its own failure, so one cannot stop the other.
 */
@Component
@ConditionalOnSchedulingEnabled
class TelemetryRefreshSchedule {

    static final long INTERVAL_MILLIS = 10_000;

    private final DeliveryGauges deliveryGauges;
    private final OutboxMetrics outboxMetrics;

    TelemetryRefreshSchedule(DeliveryGauges deliveryGauges, OutboxMetrics outboxMetrics) {
        this.deliveryGauges = deliveryGauges;
        this.outboxMetrics = outboxMetrics;
    }

    @Scheduled(fixedDelay = INTERVAL_MILLIS)
    void tick() {
        deliveryGauges.scheduledRefresh();
        outboxMetrics.scheduledRefresh();
    }
}
