package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.web.FieldViolation;
import io.github.kaulinmindiola.warden.shared.web.PageQuery;
import io.github.kaulinmindiola.warden.shared.web.PageResponse;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.workspace.application.EmailNotVerifiedException;
import io.github.kaulinmindiola.warden.workspace.application.InvalidWorkspaceException;
import io.github.kaulinmindiola.warden.workspace.application.WorkspaceService;
import io.github.kaulinmindiola.warden.workspace.application.WorkspaceView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** The caller's workspaces (UC-10, UC-11). Authenticated; served under {@code /api/v1}. */
@RestController
@RequestMapping("/workspaces")
class WorkspaceController {

    private final WorkspaceService workspaceService;

    WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * Creates a workspace owned by the caller: 201 with {@code Location}; 400 {@code
     * validation-error}; 403 {@code email-not-verified} (REQ-FUNC-027).
     */
    @PostMapping
    ResponseEntity<WorkspaceResponse> create(
            @AuthenticationPrincipal CurrentUser user, @RequestBody CreateWorkspaceRequest request) {
        WorkspaceView created;
        try {
            created = workspaceService.create(user.userId(), request.name());
        } catch (InvalidWorkspaceException invalid) {
            List<FieldViolation> violations = invalid.violations().entrySet().stream()
                    .map(violation -> new FieldViolation(violation.getKey(), violation.getValue()))
                    .toList();
            throw new ProblemException(ProblemType.VALIDATION_ERROR, violations);
        } catch (EmailNotVerifiedException notVerified) {
            throw new ProblemException(ProblemType.EMAIL_NOT_VERIFIED);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(WorkspaceResponse.from(created));
    }

    /** The caller's non-deleted workspaces with the caller's role, oldest first (REQ-FUNC-028). */
    @GetMapping
    PageResponse<WorkspaceResponse> list(@AuthenticationPrincipal CurrentUser user, @Valid PageQuery query) {
        return PageResponse.from(workspaceService.listMine(user.userId(), query.toPageable()), WorkspaceResponse::from);
    }
}
