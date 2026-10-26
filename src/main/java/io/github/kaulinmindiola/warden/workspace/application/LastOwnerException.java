package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/** The change would leave the workspace without an OWNER (BR-001, BR-011). */
public class LastOwnerException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LastOwnerException() {
        super("The workspace must keep at least one OWNER");
    }
}
