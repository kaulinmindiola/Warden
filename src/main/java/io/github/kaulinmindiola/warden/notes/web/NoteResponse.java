package io.github.kaulinmindiola.warden.notes.web;

import io.github.kaulinmindiola.warden.notes.application.NoteView;
import java.time.Instant;
import java.util.UUID;

/** A note with its body, as returned by the detail, creation and edit endpoints (SDD V08). */
public record NoteResponse(
        UUID id, String title, String body, UUID authorId, Instant createdAt, Instant updatedAt, long version) {

    static NoteResponse from(NoteView view) {
        return new NoteResponse(
                view.id(),
                view.title(),
                view.body(),
                view.authorId(),
                view.createdAt(),
                view.updatedAt(),
                view.version());
    }
}
