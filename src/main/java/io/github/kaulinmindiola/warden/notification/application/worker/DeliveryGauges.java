package io.github.kaulinmindiola.warden.notification.application.worker;

import io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus;
import io.github.kaulinmindiola.warden.notification.domain.NotificationScope;
import io.github.kaulinmindiola.warden.notification.infrastructure.DeliveryRepository;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code warden_notification_deliveries{status, scope}} gauge of SDD V19 (REQ-OBS-002): how
 * many deliveries there are in each status and scope. {@code status="dead"} is the size of the
 * dead-letter queue of each scope, the only way to observe the system-scope one (DEC-004).
 *
 * <p>Deliveries of every workspace are counted, so the count runs in a system-context transaction,
 * which only this package may open (SDD V04 R5). The gauges show the counts of the last refresh,
 * so a scrape never queries the database; {@link TelemetryRefreshSchedule} refreshes them every
 * few seconds while workers are switched on. If PostgreSQL cannot be read, the last counts are
 * kept and the next refresh tries again.
 */
@Component
public class DeliveryGauges {

    public static final String DELIVERIES_METRIC = "warden.notification.deliveries";
    public static final String STATUS_TAG = "status";
    public static final String SCOPE_TAG = "scope";

    private static final Logger log = LoggerFactory.getLogger(DeliveryGauges.class);

    private final TenantTransactions transactions;
    private final DeliveryRepository deliveries;
    private final Map<NotificationScope, Map<DeliveryStatus, AtomicLong>> counts =
            new EnumMap<>(NotificationScope.class);

    public DeliveryGauges(TenantTransactions transactions, DeliveryRepository deliveries, MeterRegistry meterRegistry) {
        this.transactions = transactions;
        this.deliveries = deliveries;
        for (NotificationScope scope : NotificationScope.values()) {
            Map<DeliveryStatus, AtomicLong> ofScope = new EnumMap<>(DeliveryStatus.class);
            for (DeliveryStatus status : DeliveryStatus.values()) {
                AtomicLong count = new AtomicLong();
                ofScope.put(status, count);
                Gauge.builder(DELIVERIES_METRIC, count, AtomicLong::get)
                        .description("Notification deliveries by status and scope, as of the last refresh")
                        .tags(STATUS_TAG, tag(status), SCOPE_TAG, tag(scope))
                        .strongReference(true)
                        .register(meterRegistry);
            }
            counts.put(scope, ofScope);
        }
    }

    /** Counts the deliveries now and shows the counts in the gauges; for the schedule and tests. */
    public void refresh() {
        List<DeliveryRepository.StatusCount> current = transactions.system(deliveries::countByStatusAndScope);
        Map<NotificationScope, Map<DeliveryStatus, Long>> fresh = new EnumMap<>(NotificationScope.class);
        for (DeliveryRepository.StatusCount count : current) {
            fresh.computeIfAbsent(count.scope(), scope -> new EnumMap<>(DeliveryStatus.class))
                    .put(count.status(), count.count());
        }
        counts.forEach((scope, ofScope) -> ofScope.forEach(
                (status, gauge) -> gauge.set(fresh.getOrDefault(scope, Map.of()).getOrDefault(status, 0L))));
    }

    /** One scheduled refresh: a failure is logged and the gauges keep their last counts. */
    void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException failure) {
            log.warn(
                    "Delivery counts could not be refreshed ({})",
                    failure.getClass().getName());
        }
    }

    /** The tag value of an enum constant, such as {@code dead} or {@code system}. */
    public static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
