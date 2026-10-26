package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/** The operation requires a verified e-mail address (BR-009, BR-010). */
public class EmailNotVerifiedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EmailNotVerifiedException() {
        super("The operation requires a verified e-mail address");
    }
}
