package io.github.kaulinmindiola.warden.notes.application;

import io.github.kaulinmindiola.warden.notes.domain.InvalidNoteException;
import io.github.kaulinmindiola.warden.notes.domain.Note;
import io.github.kaulinmindiola.warden.notes.domain.NoteContent;
import io.github.kaulinmindiola.warden.notes.infrastructure.NoteRepository;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Notes of a workspace (UC-20, UC-21). Every operation runs in a transaction scoped to the
 * workspace of the route and names the workspace in its query (SDD V07): a note of another
 * workspace is never found, so it answers 404. Authorization happens before, in the workspace
 * authorization interceptor.
 */
@Service
public class NoteService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final NoteRepository notes;
    private final TenantTransactions tenantTransactions;
    private final UuidV7Generator ids;
    private final Clock clock;

    public NoteService(NoteRepository notes, TenantTransactions tenantTransactions, UuidV7Generator ids, Clock clock) {
        this.notes = notes;
        this.tenantTransactions = tenantTransactions;
        this.ids = ids;
        this.clock = clock;
    }

    /** The notes of the workspace, newest first (REQ-FUNC-043). */
    public Page<NoteView> list(UUID workspaceId, Pageable pageable) {
        PageRequest ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
        return tenantTransactions.inWorkspace(
                workspaceId, () -> notes.findByWorkspaceId(workspaceId, ordered).map(NoteView::from));
    }

    /**
     * One note of the workspace (REQ-FUNC-043).
     *
     * @throws NoteNotFoundException if it does not exist in this workspace
     */
    public NoteView get(UUID workspaceId, UUID noteId) {
        return tenantTransactions
                .inWorkspace(
                        workspaceId,
                        () -> notes.findByIdAndWorkspaceId(noteId, workspaceId).map(NoteView::from))
                .orElseThrow(NoteNotFoundException::new);
    }

    /**
     * Creates a note authored by the caller (REQ-FUNC-044).
     *
     * @throws InvalidNoteException naming every invalid field
     */
    public NoteView create(UUID workspaceId, UUID authorId, String title, String body) {
        NoteContent content = NoteContent.of(title, body);
        return tenantTransactions.inWorkspace(workspaceId, () -> {
            Note note = Note.create(ids.next(), workspaceId, content, authorId, clock.instant());
            notes.persist(note);
            notes.flush();
            return NoteView.from(note);
        });
    }

    /**
     * Replaces the title and body if {@code version} is the current one (REQ-FUNC-045).
     *
     * @throws InvalidNoteException naming every invalid field, including a missing version
     * @throws NoteNotFoundException if the note does not exist in this workspace
     * @throws NoteVersionConflictException if the version is stale, or a concurrent edit of the
     *     same version committed first
     */
    public NoteView update(UUID workspaceId, UUID noteId, String title, String body, Long version) {
        Map<String, String> violations = new LinkedHashMap<>(NoteContent.violations(title, body));
        if (version == null) {
            violations.put("version", NoteContent.REQUIRED);
        }
        if (!violations.isEmpty()) {
            throw new InvalidNoteException(violations);
        }
        NoteContent content = NoteContent.of(title, body);
        try {
            return tenantTransactions.inWorkspace(workspaceId, () -> {
                Note note = notes.findByIdAndWorkspaceId(noteId, workspaceId).orElseThrow(NoteNotFoundException::new);
                if (note.version() != version) {
                    throw new NoteVersionConflictException();
                }
                note.edit(content, clock.instant());
                notes.flush();
                return NoteView.from(note);
            });
        } catch (OptimisticLockingFailureException | OptimisticLockException concurrentEdit) {
            throw new NoteVersionConflictException();
        }
    }

    /**
     * Deletes a note of the workspace (REQ-FUNC-046).
     *
     * @throws NoteNotFoundException if it does not exist in this workspace
     */
    public void delete(UUID workspaceId, UUID noteId) {
        int deleted = tenantTransactions.inWorkspace(workspaceId, () -> notes.deleteInWorkspace(noteId, workspaceId));
        if (deleted == 0) {
            throw new NoteNotFoundException();
        }
    }
}
