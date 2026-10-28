package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.security.RequiresWorkspaceRole;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.web.FieldViolation;
import io.github.kaulinmindiola.warden.shared.web.PageQuery;
import io.github.kaulinmindiola.warden.shared.web.PageResponse;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.workspace.application.AlreadyMemberException;
import io.github.kaulinmindiola.warden.workspace.application.InsufficientAuthorityException;
import io.github.kaulinmindiola.warden.workspace.application.InvalidInvitationException;
import io.github.kaulinmindiola.warden.workspace.application.InvitationNotFoundException;
import io.github.kaulinmindiola.warden.workspace.application.InvitationNotPendingException;
import io.github.kaulinmindiola.warden.workspace.application.InvitationService;
import io.github.kaulinmindiola.warden.workspace.application.WorkspaceNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Invitations of a workspace (UC-12, UC-13). Served under {@code /api/v1}. */
@RestController
@RequestMapping("/workspaces/{workspaceId}/invitations")
class InvitationController {

    private final InvitationService invitationService;

    InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /**
     * Invites an address: 201; 400 {@code validation-error} (including role OWNER); 409 {@code
     * already-member} (REQ-FUNC-031, REQ-FUNC-032).
     */
    @PostMapping
    @RequiresWorkspaceRole(Role.ADMIN)
    ResponseEntity<InvitationResponse> create(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody CreateInvitationRequest request) {
        try {
            InvitationResponse created = InvitationResponse.from(invitationService.create(
                    workspaceId, actor.userId(), request.email(), request.role(), request.locale()));
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (InvalidInvitationException invalid) {
            List<FieldViolation> violations = invalid.violations().entrySet().stream()
                    .map(violation -> new FieldViolation(violation.getKey(), violation.getValue()))
                    .toList();
            throw new ProblemException(ProblemType.VALIDATION_ERROR, violations);
        } catch (AlreadyMemberException alreadyMember) {
            throw new ProblemException(ProblemType.ALREADY_MEMBER);
        } catch (InsufficientAuthorityException insufficient) {
            throw new ProblemException(ProblemType.INSUFFICIENT_ROLE);
        } catch (WorkspaceNotFoundException deleted) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        }
    }

    /** The pending, unexpired invitations, newest first, without tokens (REQ-FUNC-034). */
    @GetMapping
    @RequiresWorkspaceRole(Role.ADMIN)
    PageResponse<InvitationResponse> list(@PathVariable UUID workspaceId, @Valid PageQuery query) {
        return PageResponse.from(
                invitationService.listPending(workspaceId, query.toPageable()), InvitationResponse::from);
    }

    /**
     * Revokes a pending invitation: 204; 404 if it does not exist in this workspace; 409 {@code
     * invitation-not-pending} (REQ-FUNC-035).
     */
    @DeleteMapping("/{invitationId}")
    @RequiresWorkspaceRole(Role.ADMIN)
    ResponseEntity<Void> revoke(
            @PathVariable UUID workspaceId,
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal CurrentUser actor) {
        try {
            invitationService.revoke(workspaceId, actor.userId(), invitationId);
        } catch (InvitationNotFoundException | WorkspaceNotFoundException notFound) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        } catch (InvitationNotPendingException notPending) {
            throw new ProblemException(ProblemType.INVITATION_NOT_PENDING);
        } catch (InsufficientAuthorityException insufficient) {
            throw new ProblemException(ProblemType.INSUFFICIENT_ROLE);
        }
        return ResponseEntity.noContent().build();
    }
}
