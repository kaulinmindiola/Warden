package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/**
 * The actor's role does not allow this change: an ADMIN acting on an OWNER or assigning OWNER
 * (BR-002, BR-008), or an actor whose role dropped below ADMIN.
 */
public class InsufficientAuthorityException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InsufficientAuthorityException() {
        super("The actor's role does not allow this change");
    }
}
