package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/**
 * The invitation token is malformed, unknown, used, expired, revoked or replaced, or its workspace
 * was deleted. Deliberately carries no reason: every case gets the same response (REQ-FUNC-036).
 */
public class InvalidInvitationTokenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidInvitationTokenException() {
        super("The invitation token is not valid");
    }
}
