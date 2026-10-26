package io.github.kaulinmindiola.warden.iam.application;

import java.io.Serial;

/**
 * The refresh token cannot be used: unknown, already used, expired, or of an inactive session.
 * The cause is deliberately not distinguished.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidRefreshTokenException() {
        super("The refresh token is not valid");
    }
}
