package io.github.kaulinmindiola.warden.notification.web;

import io.github.kaulinmindiola.warden.notification.application.DeliveryNotFoundException;
import io.github.kaulinmindiola.warden.notification.application.DeliveryNotRetryableException;
import io.github.kaulinmindiola.warden.notification.application.InvitationLinkUnusableException;
import io.github.kaulinmindiola.warden.notification.application.WorkspaceDeliveryService;
import io.github.kaulinmindiola.warden.notification.domain.DeliveryStatus;
import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.security.RequiresWorkspaceRole;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.web.PageQuery;
import io.github.kaulinmindiola.warden.shared.web.PageResponse;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Deliveries of a workspace and their dead-letter retry (UC-23, UC-25). Served under {@code /api/v1}. */
@RestController
@RequestMapping("/workspaces/{workspaceId}/notifications/deliveries")
class WorkspaceDeliveryController {

    private final WorkspaceDeliveryService deliveryService;

    WorkspaceDeliveryController(WorkspaceDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * The workspace-scope deliveries, newest first, optionally of one state; no content
     * (REQ-FUNC-059). An unknown state answers 400 {@code validation-error}.
     */
    @GetMapping
    @RequiresWorkspaceRole(Role.ADMIN)
    PageResponse<DeliveryResponse> list(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) DeliveryStatus status,
            @Valid PageQuery query) {
        return PageResponse.from(deliveryService.list(workspaceId, status, query.toPageable()), DeliveryResponse::from);
    }

    /**
     * Retries a dead delivery: 202 with the delivery PENDING again; 404 if the workspace has no such
     * delivery; 409 {@code delivery-not-retryable} if it is not DEAD; 409 {@code
     * invitation-not-pending} if its invitation was revoked, replaced, accepted or has expired
     * (REQ-FUNC-060).
     */
    @PostMapping("/{deliveryId}/retry")
    @RequiresWorkspaceRole(Role.ADMIN)
    ResponseEntity<RetriedDeliveryResponse> retry(
            @PathVariable UUID workspaceId, @PathVariable UUID deliveryId, @AuthenticationPrincipal CurrentUser actor) {
        try {
            deliveryService.retry(workspaceId, actor.userId(), deliveryId);
        } catch (DeliveryNotFoundException notFound) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        } catch (InvitationLinkUnusableException invitationGone) {
            throw new ProblemException(ProblemType.INVITATION_NOT_PENDING);
        } catch (DeliveryNotRetryableException notDead) {
            throw new ProblemException(ProblemType.DELIVERY_NOT_RETRYABLE);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new RetriedDeliveryResponse(deliveryId, DeliveryStatus.PENDING));
    }
}
