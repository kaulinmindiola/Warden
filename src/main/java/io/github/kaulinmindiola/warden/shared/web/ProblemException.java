package io.github.kaulinmindiola.warden.shared.web;

import java.util.List;
import java.util.Objects;

/**
 * An error of the problem-type catalogue raised by application code. Rendered by {@link
 * ProblemDetailsAdvice} with the status and title of its {@link ProblemType}.
 *
 * <p>The detail and the field violations are sent to the client: they must never contain
 * secrets, internal identifiers or information that reveals whether an account exists.
 */
public class ProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProblemType type;
    private final String detail;
    private final transient List<FieldViolation> violations;

    public ProblemException(ProblemType type) {
        this(type, type.defaultDetail());
    }

    public ProblemException(ProblemType type, String detail) {
        this(type, detail, List.of());
    }

    /** A problem with the default detail of its type and the given field violations. */
    public ProblemException(ProblemType type, List<FieldViolation> violations) {
        this(type, type.defaultDetail(), violations);
    }

    private ProblemException(ProblemType type, String detail, List<FieldViolation> violations) {
        super(Objects.requireNonNull(type, "type").slug() + ": " + Objects.requireNonNull(detail, "detail"));
        this.type = type;
        this.detail = detail;
        this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

    public ProblemType type() {
        return type;
    }

    public String detail() {
        return detail;
    }

    /** Field violations rendered as the {@code errors} member; empty for most problems. */
    public List<FieldViolation> violations() {
        return violations == null ? List.of() : violations;
    }
}
