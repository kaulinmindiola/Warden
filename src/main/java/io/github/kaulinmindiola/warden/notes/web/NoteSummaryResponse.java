package io.github.kaulinmindiola.warden.notes.web;

import io.github.kaulinmindiola.warden.notes.application.NoteView;
import java.time.Instant;
import java.util.UUID;

/** A note in a list, without its body (SDD V08). */
public record NoteSummaryResponse(
        UUID id, String title, UUID authorId, Instant createdAt, Instant updatedAt, long version) {

    static NoteSummaryResponse from(NoteView view) {
        return new NoteSummaryResponse(
                view.id(), view.title(), view.authorId(), view.createdAt(), view.updatedAt(), view.version());
    }
}
