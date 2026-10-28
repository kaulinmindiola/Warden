package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/** The address already belongs to a member of the workspace (REQ-FUNC-031, REQ-FUNC-036). */
public class AlreadyMemberException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AlreadyMemberException() {
        super("The user is already a member of this workspace");
    }
}
