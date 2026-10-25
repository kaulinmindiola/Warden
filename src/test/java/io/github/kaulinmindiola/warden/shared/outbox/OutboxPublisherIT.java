package io.github.kaulinmindiola.warden.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Outbox write side against PostgreSQL, connected as the application role. */
class OutboxPublisherIT extends IntegrationTest {

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void publishingOutsideATransactionIsRejected() {
        assertThatThrownBy(() ->
                        outbox.publish("test.OutsideTransaction", null, new TestPayload("a@mail.com", null, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(countOfType("test.OutsideTransaction")).isZero();
    }

    @Test
    @Verifies("REQ-REL-001")
    void aRollbackRemovesBothTheStateChangeAndTheEvent() {
        UUID actor = UUID.randomUUID();

        transaction().executeWithoutResult(status -> {
            auditLog.recordSuccess(AuditEntry.of(AuditAction.WORKSPACE_CREATED, actor, UUID.randomUUID()));
            outbox.publish("test.RolledBack", null, new TestPayload("a@mail.com", null, null));
            status.setRollbackOnly();
        });

        assertThat(auditRecordsOf(actor)).isZero();
        assertThat(countOfType("test.RolledBack")).isZero();
    }

    @Test
    @Verifies("REQ-REL-001")
    void aCommitStoresTheStateChangeAndTheEventWithEveryEnvelopeField() {
        Instant now = Instant.parse("2026-06-01T12:00:00.123456Z");
        clock.setInstant(now);
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        Instant secretExpiry = Instant.parse("2026-06-02T12:00:00Z");
        SealedSecret secret = new SealedSecret("bm90LWEtcmVhbC1jaXBoZXJ0ZXh0", "k1", secretExpiry);

        DomainEvent published = transaction().execute(status -> {
            auditLog.recordSuccess(AuditEntry.of(AuditAction.INVITATION_CREATED, actor, workspace));
            return outbox.publish("test.Committed", workspace, new TestPayload("ana@mail.com", secretExpiry, secret));
        });

        StoredEvent stored = jdbc().sql("""
                        SELECT type, schema_version, workspace_id, trace_id, occurred_at, published_at,
                               payload ->> 'email' AS email,
                               payload ->> 'expiresAt' AS expires_at,
                               payload -> 'secret' ->> 'ciphertext' AS ciphertext,
                               payload -> 'secret' ->> 'keyId' AS key_id
                        FROM outbox_event WHERE id = ?
                        """)
                .param(published.eventId())
                .query((rs, rowNum) -> new StoredEvent(
                        rs.getString("type"),
                        rs.getInt("schema_version"),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("trace_id"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("published_at", OffsetDateTime.class),
                        rs.getString("email"),
                        rs.getString("expires_at"),
                        rs.getString("ciphertext"),
                        rs.getString("key_id")))
                .single();

        assertThat(auditRecordsOf(actor)).isEqualTo(1);
        assertThat(published.eventId().version()).isEqualTo(7);
        assertThat(published.occurredAt()).isEqualTo(now);
        assertThat(stored.type()).isEqualTo("test.Committed");
        assertThat(stored.schemaVersion()).isEqualTo(1);
        assertThat(stored.workspaceId()).isEqualTo(workspace);
        assertThat(stored.traceId()).isEqualTo(published.traceId()).matches("[0-9a-f]{32}");
        assertThat(stored.occurredAt()).isEqualTo(now);
        assertThat(stored.publishedAt()).isNull();
        assertThat(stored.email()).isEqualTo("ana@mail.com");
        assertThat(Instant.parse(stored.expiresAt())).isEqualTo(secretExpiry);
        assertThat(stored.ciphertext()).isEqualTo("bm90LWEtcmVhbC1jaXBoZXJ0ZXh0");
        assertThat(stored.keyId()).isEqualTo("k1");
    }

    @Test
    void storesAnExplicitSchemaVersion() {
        DomainEvent published = transaction()
                .execute(status ->
                        outbox.publish("test.VersionTwo", 2, null, new TestPayload("a@mail.com", null, null)));

        Integer version = jdbc().sql("SELECT schema_version FROM outbox_event WHERE id = ?")
                .param(published.eventId())
                .query(Integer.class)
                .single();

        assertThat(version).isEqualTo(2);
    }

    @Test
    void aPayloadThatIsNotAJsonObjectIsRejected() {
        assertThatThrownBy(() -> transaction()
                        .executeWithoutResult(status -> outbox.publish("test.NotAnObject", null, "plain text")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event payload must be a JSON object");
        assertThat(countOfType("test.NotAnObject")).isZero();
    }

    private long countOfType(String type) {
        return jdbc().sql("SELECT count(*) FROM outbox_event WHERE type = ?")
                .param(type)
                .query(Long.class)
                .single();
    }

    private long auditRecordsOf(UUID actor) {
        return jdbc().sql("SELECT count(*) FROM audit_log WHERE actor_user_id = ?")
                .param(actor)
                .query(Long.class)
                .single();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** Payload shaped like the ones of SDD V10: a recipient e-mail, an expiry and a sealed secret. */
    record TestPayload(String email, Instant expiresAt, SealedSecret secret) {}

    private record StoredEvent(
            String type,
            int schemaVersion,
            UUID workspaceId,
            String traceId,
            Instant occurredAt,
            OffsetDateTime publishedAt,
            String email,
            String expiresAt,
            String ciphertext,
            String keyId) {}
}
