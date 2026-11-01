package io.github.kaulinmindiola.warden.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.outbox.DomainEvent;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.github.kaulinmindiola.warden.workspace.InvitationRevoked;
import io.github.kaulinmindiola.warden.workspace.MemberInvited;
import io.github.kaulinmindiola.warden.workspace.MemberRoleChanged;
import io.github.kaulinmindiola.warden.workspace.WorkspaceDeleted;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cancellation of obsolete deliveries (REQ-FUNC-062, REQ-SEC-005): a revoked or replaced
 * invitation and a deleted workspace cancel their unsent deliveries and drop the sealed tokens.
 * Delivery states other than PENDING are set as the superuser, because the delivery worker that
 * produces them arrives later in the phase.
 */
class DeliveryCancellationIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-07-08T10:00:00Z");

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private OutboxDispatchWorker worker;

    @Autowired
    private NotificationEventHandler handler;

    @Autowired
    private TenantTransactions tenantTransactions;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void dispatchWhatOtherTestsLeftPending() {
        clock.setInstant(NOW);
        dispatchAll();
    }

    @Test
    @Verifies({"REQ-FUNC-062", "REQ-SEC-005"})
    void aRevokedOrReplacedInvitationCancelsItsUnsentDeliveriesOnly() {
        UUID workspace = UUID.randomUUID();
        UUID pending = invite(workspace);
        UUID dead = invite(workspace);
        UUID sent = invite(workspace);
        UUID untouched = invite(workspace);
        dispatchAll();
        setStatus(dead, "DEAD");
        setStatus(sent, "SENT");

        publish(InvitationRevoked.TYPE, workspace, new InvitationRevoked(pending, InvitationRevoked.Reason.REVOKED));
        publish(InvitationRevoked.TYPE, workspace, new InvitationRevoked(dead, InvitationRevoked.Reason.REPLACED));
        publish(InvitationRevoked.TYPE, workspace, new InvitationRevoked(sent, InvitationRevoked.Reason.REVOKED));
        dispatchAll();

        assertThat(deliveryOf(pending)).containsEntry("status", "CANCELLED").containsEntry("has_secret", false);
        assertThat(deliveryOf(dead)).containsEntry("status", "CANCELLED").containsEntry("has_secret", false);
        assertThat(deliveryOf(sent)).containsEntry("status", "SENT");
        assertThat(deliveryOf(untouched)).containsEntry("status", "PENDING").containsEntry("has_secret", true);
    }

    @Test
    @Verifies("REQ-FUNC-062")
    void anInvitationRevokedBeforeItsEventWasDispatchedIsCancelledInTheSameBatch() {
        UUID workspace = UUID.randomUUID();
        UUID replaced = invite(workspace);
        publish(InvitationRevoked.TYPE, workspace, new InvitationRevoked(replaced, InvitationRevoked.Reason.REPLACED));
        UUID current = invite(workspace);

        worker.runOnce();

        assertThat(deliveryOf(replaced)).containsEntry("status", "CANCELLED").containsEntry("has_secret", false);
        assertThat(deliveryOf(current)).containsEntry("status", "PENDING").containsEntry("has_secret", true);
    }

    @Test
    @Verifies({"REQ-FUNC-062", "REQ-SEC-005"})
    void aDeletedWorkspaceCancelsEveryUnsentDeliveryOfThatWorkspaceOnly() {
        UUID deleted = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID invitationInDeleted = invite(deleted);
        UUID sentInDeleted = invite(deleted);
        publish(
                MemberRoleChanged.TYPE,
                deleted,
                new MemberRoleChanged(
                        "Acme",
                        member("affected@mail.com"),
                        List.of(member("owner@mail.com")),
                        Role.MEMBER,
                        Role.ADMIN,
                        UUID.randomUUID()));
        UUID invitationInOther = invite(other);
        dispatchAll();
        setStatus(sentInDeleted, "SENT");

        DomainEvent deletion = publish(WorkspaceDeleted.TYPE, deleted, WorkspaceDeleted.PAYLOAD);
        dispatchAll();
        handOverAgain(deletion);

        assertThat(statusesOfWorkspace(deleted))
                .containsExactlyInAnyOrder("CANCELLED", "SENT", "CANCELLED", "CANCELLED");
        assertThat(deliveryOf(invitationInDeleted)).containsEntry("has_secret", false);
        assertThat(deliveryOf(invitationInOther))
                .containsEntry("status", "PENDING")
                .containsEntry("has_secret", true);
    }

    private UUID invite(UUID workspace) {
        UUID invitationId = UUID.randomUUID();
        publish(
                MemberInvited.TYPE,
                workspace,
                new MemberInvited(
                        invitationId,
                        "Acme",
                        Role.MEMBER,
                        new Recipient(null, "invitee-" + invitationId + "@mail.com", "es"),
                        secretCipher.seal("wot_" + invitationId, EXPIRES),
                        EXPIRES));
        return invitationId;
    }

    private DomainEvent publish(String type, UUID workspaceId, Object payload) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> outbox.publish(type, workspaceId, payload));
    }

    private void dispatchAll() {
        while (worker.runOnce() > 0) {
            // keep dispatching until no pending event is left
        }
    }

    /** Hands an already dispatched event to the handler again, as after a crash before commit. */
    private void handOverAgain(DomainEvent event) {
        tenantTransactions.system(() -> {
            handler.handle(event);
            return null;
        });
    }

    private static Recipient member(String email) {
        return new Recipient(UUID.randomUUID(), email, "es");
    }

    /** Stands in for the delivery worker, which arrives later in the phase. */
    private static void setStatus(UUID invitationId, String status) {
        superuser()
                .sql("""
                        UPDATE notification_delivery
                        SET status = ?,
                            secret_ciphertext = CASE WHEN ? = 'SENT' THEN NULL ELSE secret_ciphertext END,
                            secret_key_id = CASE WHEN ? = 'SENT' THEN NULL ELSE secret_key_id END
                        WHERE invitation_id = ?
                        """)
                .param(status)
                .param(status)
                .param(status)
                .param(invitationId)
                .update();
    }

    private static Map<String, Object> deliveryOf(UUID invitationId) {
        return superuser().sql("""
                        SELECT status, secret_ciphertext IS NOT NULL AS has_secret
                        FROM notification_delivery
                        WHERE invitation_id = ?
                        """).param(invitationId).query().singleRow();
    }

    private static List<String> statusesOfWorkspace(UUID workspaceId) {
        return superuser()
                .sql("SELECT status FROM notification_delivery WHERE workspace_id = ?")
                .param(workspaceId)
                .query(String.class)
                .list();
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
