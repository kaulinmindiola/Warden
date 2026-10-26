package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.RequiresWorkspaceRole;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.web.PageQuery;
import io.github.kaulinmindiola.warden.shared.web.PageResponse;
import io.github.kaulinmindiola.warden.workspace.application.WorkspaceService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Members of a workspace (UC-15). Served under {@code /api/v1}. */
@RestController
@RequestMapping("/workspaces/{workspaceId}/members")
class WorkspaceMemberController {

    private final WorkspaceService workspaceService;

    WorkspaceMemberController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /** All members with their e-mail and role, in joining order (REQ-FUNC-037). */
    @GetMapping
    @RequiresWorkspaceRole(Role.VIEWER)
    PageResponse<MemberResponse> list(@PathVariable UUID workspaceId, @Valid PageQuery query) {
        return PageResponse.from(workspaceService.listMembers(workspaceId, query.toPageable()), MemberResponse::from);
    }
}
