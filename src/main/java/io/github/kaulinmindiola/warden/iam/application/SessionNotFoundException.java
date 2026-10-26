package io.github.kaulinmindiola.warden.iam.application;

import java.io.Serial;

/** The session does not exist or belongs to another user; deliberately not distinguished. */
public class SessionNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SessionNotFoundException() {
        super("Session not found");
    }
}
