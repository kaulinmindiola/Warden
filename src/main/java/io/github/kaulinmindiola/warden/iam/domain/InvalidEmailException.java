package io.github.kaulinmindiola.warden.iam.domain;

import java.io.Serial;

/** The value is not a well-formed e-mail address. The message never contains the value. */
public class InvalidEmailException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidEmailException() {
        super("The e-mail address is not well formed");
    }
}
