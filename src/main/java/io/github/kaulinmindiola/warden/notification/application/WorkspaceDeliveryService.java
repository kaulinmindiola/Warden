package io.github.kaulinmindiola.warden.notification.application;

import io.github.kaulinmindiola.warden.notification.domain.Delivery;
import io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus;
import io.github.kaulinmindiola.warden.notification.domain.DeliveryView;
import io.github.kaulinmindiola.warden.notification.infrastructure.DeliveryRepository;
import io.github.kaulinmindiola.warden.notification.infrastructure.DeliveryRepository.RetryCandidate;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditEntry;
import io.github.kaulinmindiola.warden.shared.audit.AuditLog;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * The deliveries of a workspace for its ADMINs and OWNERs (UC-23, UC-25). Every operation runs in
 * the workspace's tenant context, so Row Level Security hides the deliveries of other workspaces
 * and the system-scope ones, which have no workspace (DEC-004).
 */
@Service
public class WorkspaceDeliveryService {

    private final TenantTransactions transactions;
    private final DeliveryRepository deliveries;
    private final AuditLog auditLog;
    private final Clock clock;

    public WorkspaceDeliveryService(
            TenantTransactions transactions, DeliveryRepository deliveries, AuditLog auditLog, Clock clock) {
        this.transactions = transactions;
        this.deliveries = deliveries;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * The workspace's deliveries, newest first, without their content (REQ-FUNC-059).
     *
     * @param status only deliveries in this state, or all when {@code null}
     */
    public Page<DeliveryView> list(UUID workspaceId, DeliveryStatus status, Pageable pageable) {
        return transactions.inWorkspace(workspaceId, () -> deliveries.findOfWorkspace(workspaceId, status, pageable));
    }

    /**
     * Returns a dead delivery to PENDING, due now, with its attempts reset (REQ-FUNC-060), and
     * audits it as {@code DELIVERY_RETRIED}.
     *
     * @throws DeliveryNotFoundException if the workspace has no such delivery
     * @throws InvitationLinkUnusableException if it belongs to an invitation that was revoked,
     *     replaced or accepted, or whose link has expired
     * @throws DeliveryNotRetryableException if it is not DEAD
     */
    public void retry(UUID workspaceId, UUID actorId, UUID deliveryId) {
        transactions.inWorkspace(workspaceId, () -> {
            Instant now = clock.instant();
            RetryCandidate candidate =
                    deliveries.lockForRetry(workspaceId, deliveryId, now).orElseThrow(DeliveryNotFoundException::new);
            Delivery delivery = candidate.delivery();
            if (candidate.invitationId() != null && invitationGone(delivery.status(), candidate.linkUsable())) {
                throw new InvitationLinkUnusableException();
            }
            if (delivery.status() != DeliveryStatus.DEAD) {
                throw new DeliveryNotRetryableException();
            }
            delivery.requeue(now);
            deliveries.saveState(delivery);
            auditLog.recordSuccess(new AuditEntry(
                    AuditAction.DELIVERY_RETRIED, actorId, workspaceId, Map.of("deliveryId", deliveryId.toString())));
            return null;
        });
    }

    /**
     * An invitation delivery is cancelled once its invitation is revoked, replaced or accepted; a
     * dead one without a usable token can no longer carry a valid link.
     */
    private static boolean invitationGone(DeliveryStatus status, boolean linkUsable) {
        return status == DeliveryStatus.CANCELLED || (status == DeliveryStatus.DEAD && !linkUsable);
    }
}
