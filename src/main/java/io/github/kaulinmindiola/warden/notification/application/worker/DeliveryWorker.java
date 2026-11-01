package io.github.kaulinmindiola.warden.notification.application.worker;

import io.github.kaulinmindiola.warden.notification.application.ProviderFailover;
import io.github.kaulinmindiola.warden.notification.application.SendOutcome;
import io.github.kaulinmindiola.warden.notification.domain.BackoffPolicy;
import io.github.kaulinmindiola.warden.notification.domain.ClaimedDelivery;
import io.github.kaulinmindiola.warden.notification.domain.Delivery;
import io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus;
import io.github.kaulinmindiola.warden.notification.domain.EmailMessage;
import io.github.kaulinmindiola.warden.notification.domain.LinkType;
import io.github.kaulinmindiola.warden.notification.domain.RenderedMessage;
import io.github.kaulinmindiola.warden.notification.domain.Template;
import io.github.kaulinmindiola.warden.notification.domain.TemplateKey;
import io.github.kaulinmindiola.warden.notification.domain.TemplateRenderer;
import io.github.kaulinmindiola.warden.notification.infrastructure.DeliveryProperties;
import io.github.kaulinmindiola.warden.notification.infrastructure.DeliveryRepository;
import io.github.kaulinmindiola.warden.notification.infrastructure.LinkBuilder;
import io.github.kaulinmindiola.warden.notification.infrastructure.NotificationTemplateRepository;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.logging.LogFields;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Sends the due deliveries (SDD V13, V14-A5); {@link DeliveryWorkerSchedule} ticks it every second
 * while workers are switched on. Each tick:
 *
 * <ol>
 *   <li>drops the sealed tokens that have expired (REQ-SEC-005);
 *   <li>leases a batch of due deliveries for 30 s and commits, so other instances skip them;
 *   <li>sends them on virtual threads, at most {@code concurrency} at a time: renders the pinned
 *       template, opens the token into its link and calls the providers with failover;
 *   <li>records each result in its own transaction, against the state re-read under lock: a
 *       delivery cancelled meanwhile stays CANCELLED.
 * </ol>
 *
 * <p>A worker that dies after a provider accepted a message leaves the lease to lapse; the delivery
 * is then claimed and sent again with the same idempotency key, its id (at-least-once, ADR-009).
 *
 * <p>Every log line about a delivery carries {@code originTraceId}, the trace of the request that
 * produced it, and each recorded result is logged without the recipient (REQ-OBS-005, 006).
 */
@Component
public class DeliveryWorker {

    /** The link of the delivery expired before it could be sent. */
    public static final String LINK_EXPIRED = "LINK_EXPIRED";

    /** The delivery could not be composed: its pinned template or its token could not be used. */
    public static final String RENDER_FAILURE = "RENDER_FAILURE";

    /** Recorded outcomes, tagged with the state each delivery reached. */
    public static final String OUTCOME_METRIC = "warden.notification.delivery.outcomes";

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final TenantTransactions transactions;
    private final DeliveryRepository deliveries;
    private final NotificationTemplateRepository templates;
    private final TemplateRenderer renderer;
    private final SecretCipher cipher;
    private final LinkBuilder links;
    private final ProviderFailover failover;
    private final BackoffPolicy backoff;
    private final DeliveryProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public DeliveryWorker(
            TenantTransactions transactions,
            DeliveryRepository deliveries,
            NotificationTemplateRepository templates,
            TemplateRenderer renderer,
            SecretCipher cipher,
            LinkBuilder links,
            ProviderFailover failover,
            BackoffPolicy backoff,
            DeliveryProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.transactions = transactions;
        this.deliveries = deliveries;
        this.templates = templates;
        this.renderer = renderer;
        this.cipher = cipher;
        this.links = links;
        this.failover = failover;
        this.backoff = backoff;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * One scheduled tick. A failed tick, for instance while PostgreSQL is unavailable, is logged and
     * the next one tries again; leased deliveries are claimed again once their lease lapses.
     */
    public void scheduledRun() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.warn(
                    "Delivery tick failed ({}); retrying on the next tick",
                    failure.getClass().getName());
        }
    }

    /**
     * Sends one batch of due deliveries now and waits until every one is recorded. Tests call it
     * directly, since workers are switched off on the test classpath.
     *
     * @return how many deliveries were claimed
     */
    public int runOnce() {
        Instant now = clock.instant();
        transactions.system(() -> deliveries.discardExpiredSecrets(now));
        List<ClaimedDelivery> claimed = claim();
        if (claimed.isEmpty()) {
            return 0;
        }
        Semaphore permits = new Semaphore(properties.concurrency());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClaimedDelivery delivery : claimed) {
                executor.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        attempt(delivery);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        return claimed.size();
    }

    /** Leases a batch of due deliveries in its own, immediately committed, transaction. */
    List<ClaimedDelivery> claim() {
        Instant now = clock.instant();
        return transactions.system(
                () -> deliveries.claimDue(now, now.plus(properties.lease()), properties.batchSize()));
    }

    /** Composes, sends and records one claimed delivery; never throws. */
    void attempt(ClaimedDelivery claimed) {
        MDC.put(LogFields.ORIGIN_TRACE_ID, claimed.originTraceId());
        try {
            sendAndRecord(claimed);
        } finally {
            MDC.remove(LogFields.ORIGIN_TRACE_ID);
        }
    }

    private void sendAndRecord(ClaimedDelivery claimed) {
        try {
            Optional<EmailMessage> message;
            try {
                message = compose(claimed);
            } catch (RuntimeException failure) {
                log.warn(
                        "Delivery {} could not be composed ({})",
                        claimed.id(),
                        failure.getClass().getName());
                record(claimed.id(), delivery -> delivery.abandon(RENDER_FAILURE));
                return;
            }
            if (message.isEmpty()) {
                record(claimed.id(), delivery -> delivery.abandon(LINK_EXPIRED));
                return;
            }
            SendOutcome outcome = failover.send(message.get());
            Instant now = clock.instant();
            record(
                    claimed.id(),
                    delivery -> delivery.recordAttempt(
                            outcome.result(),
                            outcome.provider(),
                            now,
                            now.plus(backoff.delay(delivery.attempts() + 1))));
        } catch (RuntimeException failure) {
            log.warn(
                    "Delivery {} was not recorded ({}); it is claimed again once its lease lapses",
                    claimed.id(),
                    failure.getClass().getName());
        }
    }

    /**
     * The e-mail of a delivery, or nothing if its link has expired.
     *
     * @throws RuntimeException if the pinned template is missing or cannot be rendered, or the
     *     token cannot be opened
     */
    private Optional<EmailMessage> compose(ClaimedDelivery claimed) {
        TemplateKey key = TemplateKey.fromKey(claimed.templateKey())
                .orElseThrow(() -> new IllegalStateException("Unknown template key"));
        Template template = templates
                .findGlobalVersion(key.key(), claimed.locale(), claimed.templateVersion())
                .orElseThrow(() -> new IllegalStateException("The pinned template version does not exist"));
        Map<String, String> values = new HashMap<>(claimed.variables());
        if (LinkType.of(key).isPresent()) {
            SealedSecret secret = claimed.secret();
            if (secret == null || !secret.expiresAt().isAfter(clock.instant())) {
                return Optional.empty();
            }
            values.putAll(links.linkVariable(key, cipher.open(secret)));
        }
        RenderedMessage rendered = renderer.render(template, values);
        return Optional.of(new EmailMessage(
                claimed.id().toString(), claimed.recipientEmail(), rendered.subject(), rendered.body()));
    }

    /**
     * Applies {@code change} to the state re-read under lock and stores it. A delivery cancelled
     * while it was being sent stays CANCELLED (SDD V13).
     */
    private void record(UUID id, Consumer<Delivery> change) {
        Optional<Delivery> recorded = transactions.system(() -> {
            Optional<Delivery> current = deliveries.lockState(id);
            if (current.isEmpty() || current.get().status() == DeliveryStatus.CANCELLED) {
                return Optional.<Delivery>empty();
            }
            Delivery delivery = current.get();
            change.accept(delivery);
            deliveries.saveState(delivery);
            return Optional.of(delivery);
        });
        DeliveryStatus reached = recorded.map(Delivery::status).orElse(DeliveryStatus.CANCELLED);
        meterRegistry.counter(OUTCOME_METRIC, "status", reached.name()).increment();
        recorded.ifPresentOrElse(
                delivery -> log.info(
                        "Delivery {} is {} after {} attempts (provider {})",
                        id,
                        delivery.status(),
                        delivery.attempts(),
                        delivery.providerUsed()),
                () -> log.info("Delivery {} was cancelled meanwhile; the result is not recorded", id));
    }
}
