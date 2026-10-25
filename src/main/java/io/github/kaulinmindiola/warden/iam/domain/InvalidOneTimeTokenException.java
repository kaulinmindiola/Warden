package io.github.kaulinmindiola.warden.iam.domain;

import java.io.Serial;

/**
 * A one-time token cannot be used: it is unknown, already consumed, invalidated or expired. The
 * cause is deliberately not distinguished (REQ-FUNC-006).
 */
public class InvalidOneTimeTokenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidOneTimeTokenException() {
        super("The one-time token is not valid");
    }
}
