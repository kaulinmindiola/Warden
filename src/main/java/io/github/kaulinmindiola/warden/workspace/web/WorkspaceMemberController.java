package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.security.RequiresWorkspaceRole;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.web.FieldViolation;
import io.github.kaulinmindiola.warden.shared.web.PageQuery;
import io.github.kaulinmindiola.warden.shared.web.PageResponse;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.workspace.application.InsufficientAuthorityException;
import io.github.kaulinmindiola.warden.workspace.application.InvalidMembershipException;
import io.github.kaulinmindiola.warden.workspace.application.LastOwnerException;
import io.github.kaulinmindiola.warden.workspace.application.MemberNotFoundException;
import io.github.kaulinmindiola.warden.workspace.application.MembershipService;
import io.github.kaulinmindiola.warden.workspace.application.WorkspaceNotFoundException;
import io.github.kaulinmindiola.warden.workspace.application.WorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Members of a workspace (UC-15, UC-16). Served under {@code /api/v1}. */
@RestController
@RequestMapping("/workspaces/{workspaceId}/members")
class WorkspaceMemberController {

    private final WorkspaceService workspaceService;
    private final MembershipService membershipService;

    WorkspaceMemberController(WorkspaceService workspaceService, MembershipService membershipService) {
        this.workspaceService = workspaceService;
        this.membershipService = membershipService;
    }

    /** All members with their e-mail and role, in joining order (REQ-FUNC-037). */
    @GetMapping
    @RequiresWorkspaceRole(Role.VIEWER)
    PageResponse<MemberResponse> list(@PathVariable UUID workspaceId, @Valid PageQuery query) {
        return PageResponse.from(workspaceService.listMembers(workspaceId, query.toPageable()), MemberResponse::from);
    }

    /**
     * Changes a member's role: 200; 400 {@code validation-error}; 403 {@code insufficient-role} when
     * an ADMIN acts on an OWNER or assigns OWNER; 404 if the user is not a member of this workspace;
     * 409 {@code last-owner} (REQ-FUNC-038, REQ-FUNC-039).
     */
    @PutMapping("/{userId}/role")
    @RequiresWorkspaceRole(Role.ADMIN)
    MemberResponse changeRole(
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody ChangeRoleRequest request) {
        try {
            return MemberResponse.from(
                    membershipService.changeRole(workspaceId, actor.userId(), userId, request.role()));
        } catch (InvalidMembershipException invalid) {
            List<FieldViolation> violations = invalid.violations().entrySet().stream()
                    .map(violation -> new FieldViolation(violation.getKey(), violation.getValue()))
                    .toList();
            throw new ProblemException(ProblemType.VALIDATION_ERROR, violations);
        } catch (InsufficientAuthorityException insufficient) {
            throw new ProblemException(ProblemType.INSUFFICIENT_ROLE);
        } catch (MemberNotFoundException | WorkspaceNotFoundException notFound) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        } catch (LastOwnerException lastOwner) {
            throw new ProblemException(ProblemType.LAST_OWNER);
        }
    }
}
