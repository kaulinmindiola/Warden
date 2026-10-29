package io.github.kaulinmindiola.warden.shared.web;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An error of the problem-type catalogue raised by application code. Rendered by {@link
 * ProblemDetailsAdvice} with the status and title of its {@link ProblemType}.
 *
 * <p>The detail and the field violations are sent to the client: they must never contain
 * secrets, internal identifiers or information that reveals whether an account exists.
 *
 * <p>A problem created with {@link #withRetryAfter} is rendered with a {@code Retry-After} header
 * (429 {@code rate-limited}, 503 {@code service-unavailable}).
 */
public class ProblemException extends RuntimeException {

    /** {@code Retry-After} of every 503 {@code service-unavailable} response (SDD V17). */
    public static final Duration SERVICE_UNAVAILABLE_RETRY_AFTER = Duration.ofSeconds(5);

    private static final long serialVersionUID = 1L;

    private final ProblemType type;
    private final String detail;
    private final transient List<FieldViolation> violations;
    private final Duration retryAfter;

    public ProblemException(ProblemType type) {
        this(type, Objects.requireNonNull(type, "type").defaultDetail());
    }

    public ProblemException(ProblemType type, String detail) {
        this(type, detail, List.of(), null);
    }

    /** A problem with the default detail of its type and the given field violations. */
    public ProblemException(ProblemType type, List<FieldViolation> violations) {
        this(type, Objects.requireNonNull(type, "type").defaultDetail(), violations, null);
    }

    private ProblemException(ProblemType type, String detail, List<FieldViolation> violations, Duration retryAfter) {
        super(Objects.requireNonNull(type, "type").slug() + ": " + Objects.requireNonNull(detail, "detail"));
        this.type = type;
        this.detail = detail;
        this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
        this.retryAfter = retryAfter;
    }

    /**
     * A problem with the default detail of its type that tells the client when to retry.
     *
     * @param retryAfter positive time after which the request may succeed
     */
    public static ProblemException withRetryAfter(ProblemType type, Duration retryAfter) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (retryAfter.isZero() || retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must be positive");
        }
        return new ProblemException(type, type.defaultDetail(), List.of(), retryAfter);
    }

    /**
     * 503 {@code service-unavailable} with {@code Retry-After: 5}: a dependency the request needs
     * cannot answer, so the request is denied rather than answered without it (REQ-REL-009).
     */
    public static ProblemException serviceUnavailable() {
        return withRetryAfter(ProblemType.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE_RETRY_AFTER);
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

    /** When the client may retry; empty for problems without a {@code Retry-After} header. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * The {@code Retry-After} value in whole seconds, rounded up so that a client that waits exactly
     * that long is never early; at least 1.
     */
    public OptionalLong retryAfterSeconds() {
        if (retryAfter == null) {
            return OptionalLong.empty();
        }
        long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
        return OptionalLong.of(Math.max(1L, seconds));
    }
}
