package io.github.kaulinmindiola.warden.notes.web;

import io.github.kaulinmindiola.warden.notes.application.NoteNotFoundException;
import io.github.kaulinmindiola.warden.notes.application.NoteService;
import io.github.kaulinmindiola.warden.notes.application.NoteVersionConflictException;
import io.github.kaulinmindiola.warden.notes.application.NoteView;
import io.github.kaulinmindiola.warden.notes.domain.InvalidNoteException;
import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.security.RequiresWorkspaceRole;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.web.FieldViolation;
import io.github.kaulinmindiola.warden.shared.web.PageQuery;
import io.github.kaulinmindiola.warden.shared.web.PageResponse;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Notes of a workspace (UC-20, UC-21). Served under {@code /api/v1}. */
@RestController
@RequestMapping("/workspaces/{workspaceId}/notes")
class NoteController {

    private final NoteService noteService;

    NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    /** The notes of the workspace, newest first, without bodies (REQ-FUNC-043). */
    @GetMapping
    @RequiresWorkspaceRole(Role.VIEWER)
    PageResponse<NoteSummaryResponse> list(@PathVariable UUID workspaceId, @Valid PageQuery query) {
        return PageResponse.from(noteService.list(workspaceId, query.toPageable()), NoteSummaryResponse::from);
    }

    /** Creates a note authored by the caller: 201 with {@code Location}, or 400 (REQ-FUNC-044). */
    @PostMapping
    @RequiresWorkspaceRole(Role.MEMBER)
    ResponseEntity<NoteResponse> create(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser user,
            @RequestBody CreateNoteRequest request) {
        NoteView created;
        try {
            created = noteService.create(workspaceId, user.userId(), request.title(), request.body());
        } catch (InvalidNoteException invalid) {
            throw validationError(invalid);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{noteId}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(NoteResponse.from(created));
    }

    /** One note: 200, or 404 if it does not exist in this workspace (REQ-FUNC-043). */
    @GetMapping("/{noteId}")
    @RequiresWorkspaceRole(Role.VIEWER)
    NoteResponse get(@PathVariable UUID workspaceId, @PathVariable UUID noteId) {
        try {
            return NoteResponse.from(noteService.get(workspaceId, noteId));
        } catch (NoteNotFoundException notFound) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        }
    }

    /** Edits a note: 200, 400, 404, or 409 {@code version-conflict} (REQ-FUNC-045). */
    @PutMapping("/{noteId}")
    @RequiresWorkspaceRole(Role.MEMBER)
    NoteResponse update(
            @PathVariable UUID workspaceId, @PathVariable UUID noteId, @RequestBody UpdateNoteRequest request) {
        try {
            return NoteResponse.from(
                    noteService.update(workspaceId, noteId, request.title(), request.body(), request.version()));
        } catch (InvalidNoteException invalid) {
            throw validationError(invalid);
        } catch (NoteNotFoundException notFound) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        } catch (NoteVersionConflictException conflict) {
            throw new ProblemException(ProblemType.VERSION_CONFLICT);
        }
    }

    /** Deletes a note: 204, or 404 if it does not exist in this workspace (REQ-FUNC-046). */
    @DeleteMapping("/{noteId}")
    @RequiresWorkspaceRole(Role.MEMBER)
    ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID noteId) {
        try {
            noteService.delete(workspaceId, noteId);
        } catch (NoteNotFoundException notFound) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    private static ProblemException validationError(InvalidNoteException invalid) {
        List<FieldViolation> violations = invalid.violations().entrySet().stream()
                .map(violation -> new FieldViolation(violation.getKey(), violation.getValue()))
                .toList();
        return new ProblemException(ProblemType.VALIDATION_ERROR, violations);
    }
}
