package io.github.kaulinmindiola.warden.notes.application;

import java.io.Serial;

/** The note does not exist in the workspace of the request. */
public class NoteNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NoteNotFoundException() {
        super("The note does not exist in this workspace");
    }
}
