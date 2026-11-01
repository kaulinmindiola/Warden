package io.github.kaulinmindiola.warden.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.WardenApplication;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox dispatch against PostgreSQL (REQ-REL-001, part of REQ-SEC-005), driven through the
 * notification worker as in production.
 *
 * <p>A recording handler stands in for the notification handler. It consumes only the {@code
 * dispatchtest.*} events of this class, and for each one records the system context it saw and
 * writes an audit record whose actor is the event id: that record shows whether the handler's
 * writes committed with the event or were rolled back with it.
 *
 * <p>Other integration tests leave events pending, because workers are off on the test
 * classpath. Every test starts by dispatching them, so that each batch holds only its own events.
 */
class OutboxDispatcherIT extends IntegrationTest {

    private static final String CIPHERTEXT = "c2VhbGVkLWZvci1kaXNwYXRjaA";

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private OutboxDispatchWorker worker;

    @Autowired
    private RecordingHandler handler;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @BeforeEach
    void dispatchWhatOtherTestsLeftPending() {
        handler.failOn(null);
        while (worker.runOnce() > 0) {
            // keep dispatching until no pending event is left
        }
        handler.clear();
    }

    @Test
    @Verifies("REQ-REL-001")
    void aCommittedEventIsHandedToTheHandlerOnceAndMarkedPublished() {
        Instant occurred = Instant.parse("2026-07-01T10:00:00.123456Z");
        clock.setInstant(occurred);
        UUID workspace = UUID.randomUUID();
        DomainEvent published = publish("dispatchtest.Committed", workspace, null);
        Instant dispatchedAt = occurred.plusSeconds(1);
        clock.setInstant(dispatchedAt);

        int dispatched = worker.runOnce();

        assertThat(dispatched).isEqualTo(1);
        assertThat(handler.callsFor(published.eventId())).isEqualTo(1);
        DomainEvent received = handler.received(published.eventId());
        assertThat(received.type()).isEqualTo("dispatchtest.Committed");
        assertThat(received.schemaVersion()).isEqualTo(1);
        assertThat(received.workspaceId()).isEqualTo(workspace);
        assertThat(received.traceId()).isEqualTo(published.traceId());
        assertThat(received.occurredAt()).isEqualTo(occurred);
        assertThat(received.payload()).startsWith("{").contains("ana@mail.com");
        assertThat(publishedAt(published.eventId())).contains(dispatchedAt);

        assertThat(worker.runOnce()).isZero();
        assertThat(handler.callsFor(published.eventId())).isEqualTo(1);
    }

    @Test
    void theHandlerRunsInTheDispatchTransactionWithTheSystemContext() {
        DomainEvent published = publish("dispatchtest.SystemContext", null, null);

        worker.runOnce();

        assertThat(handler.systemContextSeenBy(published.eventId())).isEqualTo("on");
        assertThat(auditRecordsOf(published.eventId())).isEqualTo(1);
    }

    @Test
    @Verifies("REQ-SEC-005")
    void theSealedSecretIsHandedToTheHandlerAndThenRemovedFromTheEvent() {
        DomainEvent published = publish("dispatchtest.WithSecret", null, secret());
        assertThat(storedPayload(published.eventId())).contains(CIPHERTEXT);

        worker.runOnce();

        assertThat(handler.received(published.eventId()).payload()).contains(CIPHERTEXT);
        assertThat(storedPayload(published.eventId()))
                .doesNotContain(CIPHERTEXT)
                .doesNotContain("\"secret\"")
                .contains("ana@mail.com");
    }

    @Test
    @Verifies("REQ-REL-001")
    void aFailingEventStaysPendingWithItsSecretAndDoesNotHoldBackTheRestOfTheBatch() {
        Instant start = Instant.parse("2026-07-01T10:00:00Z");
        clock.setInstant(start);
        DomainEvent poison = publish("dispatchtest.Poison", null, secret());
        clock.setInstant(start.plusSeconds(1));
        DomainEvent healthy = publish("dispatchtest.Healthy", null, secret());
        double failuresBefore = failureCount();
        handler.failOn("dispatchtest.Poison");

        int dispatched = worker.runOnce();

        assertThat(dispatched).isEqualTo(1);
        assertThat(failureCount()).isEqualTo(failuresBefore + 1);
        assertThat(publishedAt(poison.eventId())).isEmpty();
        assertThat(storedPayload(poison.eventId())).contains(CIPHERTEXT);
        assertThat(auditRecordsOf(poison.eventId()))
                .as("the failed handler's writes")
                .isZero();
        assertThat(publishedAt(healthy.eventId())).isPresent();
        assertThat(auditRecordsOf(healthy.eventId())).isEqualTo(1);

        handler.failOn(null);

        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(publishedAt(poison.eventId())).isPresent();
        assertThat(storedPayload(poison.eventId())).doesNotContain(CIPHERTEXT);
        assertThat(auditRecordsOf(poison.eventId())).isEqualTo(1);
    }

    @Test
    void concurrentDispatchersHandEachEventToTheHandlerExactlyOnce() throws Exception {
        int events = OutboxDispatcher.BATCH_SIZE * 2 + 50;
        List<UUID> ids = transaction()
                .execute(status -> IntStream.range(0, events)
                        .mapToObj(i -> outbox.publish(
                                        "dispatchtest.Concurrent", null, new DispatchPayload("ana@mail.com", null))
                                .eventId())
                        .toList());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    int handled = 0;
                    int dispatched;
                    while ((dispatched = worker.runOnce()) > 0) {
                        handled += dispatched;
                    }
                    return handled;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get(60, TimeUnit.SECONDS);
            }

            assertThat(total).isEqualTo(events);
            assertThat(ids).allSatisfy(id -> assertThat(handler.callsFor(id)).isEqualTo(1));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Verifies("REQ-REL-001")
    void anEventCommittedBeforeACrashIsDispatchedByTheNextInstanceToStart() {
        DomainEvent committed = publish("dispatchtest.BeforeCrash", null, secret());
        // Workers are off in tests: this instance never dispatches, exactly as one that stopped
        // between the commit and the next tick.
        assertThat(publishedAt(committed.eventId())).isEmpty();

        try (ConfigurableApplicationContext nextInstance = startNextInstance()) {
            OutboxDispatchWorker nextWorker = nextInstance.getBean(OutboxDispatchWorker.class);
            while (publishedAt(committed.eventId()).isEmpty() && nextWorker.runOnce() > 0) {
                // the next instance dispatches whatever is pending, oldest first
            }
        }

        assertThat(publishedAt(committed.eventId())).isPresent();
        assertThat(storedPayload(committed.eventId())).doesNotContain(CIPHERTEXT);
        assertThat(handler.callsFor(committed.eventId()))
                .as("this instance's handler")
                .isZero();
    }

    /**
     * Starts the application again against the shared containers, as a new process would after a
     * crash, with the settings of the test classpath. Started outside the test framework, it also
     * picks up the test configurations on the classpath, a recording handler of its own among them;
     * the assertions only look at the database and at the handler of this test's context.
     */
    private ConfigurableApplicationContext startNextInstance() {
        List<String> arguments = new ArrayList<>();
        for (String name : List.of(
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.flyway.url",
                "spring.flyway.user",
                "spring.flyway.password",
                "spring.data.redis.host",
                "spring.data.redis.port")) {
            arguments.add("--" + name + "=" + environment.getRequiredProperty(name));
        }
        arguments.add("--server.port=0");
        arguments.add("--management.server.port=0");
        return new SpringApplicationBuilder(WardenApplication.class)
                .profiles("test")
                .run(arguments.toArray(String[]::new));
    }

    private DomainEvent publish(String type, UUID workspaceId, SealedSecret secret) {
        return transaction()
                .execute(status -> outbox.publish(type, workspaceId, new DispatchPayload("ana@mail.com", secret)));
    }

    private static SealedSecret secret() {
        return new SealedSecret(CIPHERTEXT, "k1", Instant.parse("2026-07-02T10:00:00Z"));
    }

    private Optional<Instant> publishedAt(UUID eventId) {
        return jdbc().sql("SELECT published_at FROM outbox_event WHERE id = ?")
                .param(eventId)
                .query((row, rowNumber) -> Optional.ofNullable(row.getObject("published_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant))
                .single();
    }

    private String storedPayload(UUID eventId) {
        return jdbc().sql("SELECT payload::text FROM outbox_event WHERE id = ?")
                .param(eventId)
                .query(String.class)
                .single();
    }

    private long auditRecordsOf(UUID actor) {
        return jdbc().sql("SELECT count(*) FROM audit_log WHERE actor_user_id = ?")
                .param(actor)
                .query(Long.class)
                .single();
    }

    private double failureCount() {
        return meterRegistry.get(OutboxDispatcher.FAILURE_METRIC).counter().count();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** Payload shaped like those of SDD V10: a recipient e-mail and an optional sealed secret. */
    record DispatchPayload(String email, SealedSecret secret) {}

    /**
     * Records each {@code dispatchtest.*} event it consumes, writes an audit record for it and,
     * when told to, fails after that write.
     */
    static final class RecordingHandler implements OutboxEventHandler {

        private final AuditLog auditLog;
        private final JdbcClient jdbc;
        private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Map<UUID, DomainEvent> received = new ConcurrentHashMap<>();
        private final Map<UUID, String> systemContexts = new ConcurrentHashMap<>();
        private volatile String failingType;

        RecordingHandler(AuditLog auditLog, JdbcClient jdbc) {
            this.auditLog = auditLog;
            this.jdbc = jdbc;
        }

        @Override
        public void handle(DomainEvent event) {
            if (!event.type().startsWith("dispatchtest.")) {
                return;
            }
            calls.computeIfAbsent(event.eventId(), id -> new AtomicInteger()).incrementAndGet();
            received.put(event.eventId(), event);
            systemContexts.put(
                    event.eventId(),
                    jdbc.sql("SELECT coalesce(current_setting('app.system_context', true), '')")
                            .query(String.class)
                            .single());
            auditLog.recordSuccess(AuditEntry.of(AuditAction.WORKSPACE_CREATED, event.eventId(), event.workspaceId()));
            if (event.type().equals(failingType)) {
                throw new IllegalStateException("Injected handler failure");
            }
        }

        void failOn(String type) {
            this.failingType = type;
        }

        void clear() {
            calls.clear();
            received.clear();
            systemContexts.clear();
        }

        int callsFor(UUID eventId) {
            AtomicInteger count = calls.get(eventId);
            return count == null ? 0 : count.get();
        }

        DomainEvent received(UUID eventId) {
            return received.get(eventId);
        }

        String systemContextSeenBy(UUID eventId) {
            return systemContexts.get(eventId);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingHandlerConfiguration {

        @Bean
        RecordingHandler recordingHandler(AuditLog auditLog, JdbcClient jdbc) {
            return new RecordingHandler(auditLog, jdbc);
        }
    }
}
