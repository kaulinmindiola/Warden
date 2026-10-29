package io.github.kaulinmindiola.warden.shared.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Renders every error raised in Spring MVC as RFC 9457 problem details (REQ-INT-005,
 * REQ-COMP-001): {@code type}, {@code title}, {@code status}, {@code detail}, {@code instance},
 * {@code traceId} and, for validation errors, {@code errors}.
 *
 * <p>Unexpected exceptions become a generic {@code internal-error}: no message, stack trace or
 * class name reaches the client (REQ-SEC-017); the exception is logged with the same trace id.
 * Conversion failures report a generic reason because framework messages contain class names.
 * Spring Security exceptions are rethrown so the security filter chain answers them. A problem
 * that carries a retry time is sent with a {@code Retry-After} header in whole seconds.
 *
 * <p>A failure caused by PostgreSQL being unreachable becomes 503 {@code service-unavailable} with
 * {@code Retry-After: 5} (REQ-REL-009): the request is denied, never answered without the data.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    private static final String INVALID_VALUE = "has an invalid value";

    private final ObjectProvider<Tracer> tracer;

    public ProblemDetailsAdvice(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<ProblemDetail> handleProblem(ProblemException ex, HttpServletRequest request) {
        return problem(ex, request, traceId());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            String reason = error.isBindingFailure() ? INVALID_VALUE : reason(error.getDefaultMessage());
            violations.add(new FieldViolation(error.getField(), reason));
        }
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            violations.add(new FieldViolation(error.getObjectName(), reason(error.getDefaultMessage())));
        }
        return validationProblem(violations, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleInvalidParameters(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                violations.add(new FieldViolation(name == null ? "" : name, reason(error.getDefaultMessage())));
            }
        }
        return validationProblem(violations, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return validationProblem(List.of(new FieldViolation(ex.getName(), INVALID_VALUE)), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return validationProblem(List.of(new FieldViolation(ex.getParameterName(), "is required")), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        ProblemType type = hasCause(ex, RequestBodyTooLargeException.class)
                ? ProblemType.PAYLOAD_TOO_LARGE
                : ProblemType.MALFORMED_REQUEST;
        return respond(type, type.defaultDetail(), List.of(), new HttpHeaders(), request, traceId());
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    ResponseEntity<ProblemDetail> handleBodyTooLarge(RequestBodyTooLargeException ex, HttpServletRequest request) {
        return respond(
                ProblemType.PAYLOAD_TOO_LARGE,
                ProblemType.PAYLOAD_TOO_LARGE.defaultDetail(),
                List.of(),
                new HttpHeaders(),
                request,
                traceId());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleAnyOther(Exception ex, HttpServletRequest request) throws Exception {
        if (ex instanceof AuthenticationException || ex instanceof AccessDeniedException) {
            throw ex;
        }
        if (hasCause(ex, RequestBodyTooLargeException.class)) {
            return handleBodyTooLarge(new RequestBodyTooLargeException(RequestSizeLimitFilter.MAX_BODY_BYTES), request);
        }
        if (DatabaseUnavailable.causedBy(ex)) {
            String traceId = traceId();
            log.warn(
                    "PostgreSQL is unavailable ({}); the request is denied with 503, traceId={}",
                    DatabaseUnavailable.describe(ex),
                    traceId);
            return problem(ProblemException.serviceUnavailable(), request, traceId);
        }
        if (ex instanceof ErrorResponse errorResponse) {
            ProblemType type = forFrameworkStatus(errorResponse.getStatusCode().value());
            if (type != null) {
                return respond(type, type.defaultDetail(), List.of(), errorResponse.getHeaders(), request, traceId());
            }
        }
        String traceId = traceId();
        log.error("Unhandled exception, traceId={}", traceId, ex);
        return respond(
                ProblemType.INTERNAL_ERROR,
                ProblemType.INTERNAL_ERROR.defaultDetail(),
                List.of(),
                new HttpHeaders(),
                request,
                traceId);
    }

    private static ResponseEntity<ProblemDetail> problem(
            ProblemException ex, HttpServletRequest request, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        ex.retryAfterSeconds().ifPresent(seconds -> headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
        return respond(ex.type(), ex.detail(), ex.violations(), headers, request, traceId);
    }

    private ResponseEntity<ProblemDetail> validationProblem(
            List<FieldViolation> violations, HttpServletRequest request) {
        return respond(
                ProblemType.VALIDATION_ERROR,
                ProblemType.VALIDATION_ERROR.defaultDetail(),
                violations,
                new HttpHeaders(),
                request,
                traceId());
    }

    private static ResponseEntity<ProblemDetail> respond(
            ProblemType type,
            String detail,
            List<FieldViolation> violations,
            HttpHeaders headers,
            HttpServletRequest request,
            String traceId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
        problem.setType(type.uri());
        problem.setTitle(type.title());
        problem.setInstance(instanceOf(request));
        problem.setProperty("traceId", traceId);
        if (!violations.isEmpty()) {
            problem.setProperty("errors", violations);
        }
        return ResponseEntity.status(type.status())
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** Maps the status of a Spring MVC exception to the catalogue; {@code null} means unexpected. */
    private static ProblemType forFrameworkStatus(int status) {
        return switch (status) {
            case 400 -> ProblemType.VALIDATION_ERROR;
            case 404 -> ProblemType.NOT_FOUND;
            case 405 -> ProblemType.METHOD_NOT_ALLOWED;
            case 406 -> ProblemType.NOT_ACCEPTABLE;
            case 413 -> ProblemType.PAYLOAD_TOO_LARGE;
            case 415 -> ProblemType.UNSUPPORTED_MEDIA_TYPE;
            case 503 -> ProblemType.SERVICE_UNAVAILABLE;
            default -> null;
        };
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
        }
        return false;
    }

    private static URI instanceOf(HttpServletRequest request) {
        try {
            return URI.create(request.getRequestURI());
        } catch (IllegalArgumentException invalidUri) {
            return null;
        }
    }

    private static String reason(String message) {
        return message == null || message.isBlank() ? "is invalid" : message;
    }

    private String traceId() {
        Tracer current = tracer.getIfAvailable();
        if (current != null) {
            Span span = current.currentSpan();
            if (span != null) {
                String id = span.context().traceId();
                if (id != null && !id.isBlank() && !id.chars().allMatch(c -> c == '0')) {
                    return id;
                }
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
