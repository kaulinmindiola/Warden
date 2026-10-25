package io.github.kaulinmindiola.warden.iam.application;

import java.io.Serial;

/**
 * The e-mail or the password is wrong. Deliberately does not say which, nor whether the account
 * exists (REQ-FUNC-009, BR-007).
 */
public class InvalidCredentialsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
