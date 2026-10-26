package io.github.kaulinmindiola.warden.workspace.domain;

import java.io.Serial;

/** A workspace name is empty, blank or longer than {@value WorkspaceName#MAX_LENGTH} characters. */
public class InvalidWorkspaceNameException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidWorkspaceNameException() {
        super("A workspace name must have between 1 and " + WorkspaceName.MAX_LENGTH + " characters");
    }
}
