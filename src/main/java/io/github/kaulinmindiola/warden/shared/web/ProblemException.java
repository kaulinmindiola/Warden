package io.github.kaulinmindiola.warden.shared.web;

import java.util.Objects;

/**
 * An error of the problem-type catalogue raised by application code. Rendered by {@link
 * ProblemDetailsAdvice} with the status and title of its {@link ProblemType}.
 *
 * <p>The detail is sent to the client: it must never contain secrets, internal identifiers or
 * information that reveals whether an account exists.
 */
public class ProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProblemType type;
    private final String detail;

    public ProblemException(ProblemType type) {
        this(type, type.defaultDetail());
    }

    public ProblemException(ProblemType type, String detail) {
        super(Objects.requireNonNull(type, "type").slug() + ": " + Objects.requireNonNull(detail, "detail"));
        this.type = type;
        this.detail = detail;
    }

    public ProblemType type() {
        return type;
    }

    public String detail() {
        return detail;
    }
}
