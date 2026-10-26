package io.github.kaulinmindiola.warden.notes.application;

import java.io.Serial;

/** An edit was based on a version that is no longer current (REQ-FUNC-045). */
public class NoteVersionConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NoteVersionConflictException() {
        super("The note was modified by another request");
    }
}
