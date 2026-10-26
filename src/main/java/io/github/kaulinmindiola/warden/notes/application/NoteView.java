package io.github.kaulinmindiola.warden.notes.application;

import io.github.kaulinmindiola.warden.notes.domain.Note;
import java.time.Instant;
import java.util.UUID;

/**
 * A note as returned by the use cases.
 *
 * @param id note id
 * @param title title
 * @param body body
 * @param authorId the member who created it
 * @param createdAt creation time
 * @param updatedAt last edit, or the creation time
 * @param version the version an edit must state
 */
public record NoteView(
        UUID id, String title, String body, UUID authorId, Instant createdAt, Instant updatedAt, long version) {

    static NoteView from(Note note) {
        return new NoteView(
                note.id(),
                note.title(),
                note.body(),
                note.authorId(),
                note.createdAt(),
                note.updatedAt(),
                note.version());
    }
}
