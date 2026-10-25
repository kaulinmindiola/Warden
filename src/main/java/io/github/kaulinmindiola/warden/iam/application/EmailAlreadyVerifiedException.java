package io.github.kaulinmindiola.warden.iam.application;

import java.io.Serial;

/** A verification link was requested for an e-mail that is already verified (REQ-FUNC-007). */
public class EmailAlreadyVerifiedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EmailAlreadyVerifiedException() {
        super("The e-mail address is already verified");
    }
}
