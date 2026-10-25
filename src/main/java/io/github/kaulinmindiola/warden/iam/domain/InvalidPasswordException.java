package io.github.kaulinmindiola.warden.iam.domain;

import java.io.Serial;

/** The password does not satisfy the policy. The message never contains the password. */
public class InvalidPasswordException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Which rule of REQ-FUNC-003 the password breaks. */
    public enum Reason {
        TOO_SHORT,
        TOO_LONG,
        TOO_COMMON
    }

    private final Reason reason;

    public InvalidPasswordException(Reason reason) {
        super("The password is rejected by the password policy: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
