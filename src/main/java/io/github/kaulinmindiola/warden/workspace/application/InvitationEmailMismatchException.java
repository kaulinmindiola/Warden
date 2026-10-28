package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/** The caller's verified e-mail differs from the invited one; the token is not consumed (BR-009). */
public class InvitationEmailMismatchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvitationEmailMismatchException() {
        super("The invitation was sent to another e-mail address");
    }
}
