package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/** The workspace does not exist or is deleted (BR-012). */
public class WorkspaceNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WorkspaceNotFoundException() {
        super("The workspace does not exist");
    }
}
