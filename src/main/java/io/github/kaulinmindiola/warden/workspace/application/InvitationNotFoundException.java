package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/** The invitation does not exist in the workspace of the request. */
public class InvitationNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvitationNotFoundException() {
        super("The invitation does not exist in this workspace");
    }
}
