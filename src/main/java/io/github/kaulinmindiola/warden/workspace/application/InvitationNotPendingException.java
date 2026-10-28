package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/** The invitation was accepted, revoked, replaced or has expired (REQ-FUNC-035). */
public class InvitationNotPendingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvitationNotPendingException() {
        super("The invitation is no longer pending");
    }
}
