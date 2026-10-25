package io.github.kaulinmindiola.warden.shared.web;

import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * Catalogue of error types (SDD V08). Each type has a stable URI {@code urn:warden:problem:<slug>},
 * an HTTP status and fixed texts. Titles and default details never contain request data, so they
 * cannot reveal internals or the existence of accounts.
 *
 * <p>Documented in {@code docs/api/problem-types.md}; any change here must be reflected there.
 */
public enum ProblemType {
    VALIDATION_ERROR(
            "validation-error", HttpStatus.BAD_REQUEST, "Validation error", "The request contains invalid values."),
    MALFORMED_REQUEST(
            "malformed-request",
            HttpStatus.BAD_REQUEST,
            "Malformed request",
            "The request body or headers could not be read."),
    INVALID_ONE_TIME_TOKEN(
            "invalid-one-time-token",
            HttpStatus.BAD_REQUEST,
            "Invalid one-time token",
            "The token is invalid or has expired."),
    PREFERENCE_NOT_OPTIONAL(
            "preference-not-optional",
            HttpStatus.BAD_REQUEST,
            "Preference not optional",
            "This notification cannot be disabled."),
    UNAUTHENTICATED(
            "unauthenticated",
            HttpStatus.UNAUTHORIZED,
            "Unauthenticated",
            "Authentication is required to access this resource."),
    INVALID_CREDENTIALS(
            "invalid-credentials",
            HttpStatus.UNAUTHORIZED,
            "Invalid credentials",
            "The e-mail address or password is incorrect."),
    INVALID_REFRESH_TOKEN(
            "invalid-refresh-token",
            HttpStatus.UNAUTHORIZED,
            "Invalid refresh token",
            "The refresh token is invalid or has expired."),
    INSUFFICIENT_ROLE(
            "insufficient-role", HttpStatus.FORBIDDEN, "Insufficient role", "Your role does not allow this operation."),
    EMAIL_NOT_VERIFIED(
            "email-not-verified",
            HttpStatus.FORBIDDEN,
            "E-mail not verified",
            "Verify your e-mail address before performing this operation."),
    INVITATION_EMAIL_MISMATCH(
            "invitation-email-mismatch",
            HttpStatus.FORBIDDEN,
            "Invitation e-mail mismatch",
            "This invitation was sent to a different e-mail address."),
    NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Not found", "The requested resource does not exist."),
    METHOD_NOT_ALLOWED(
            "method-not-allowed",
            HttpStatus.METHOD_NOT_ALLOWED,
            "Method not allowed",
            "This HTTP method is not supported for the requested resource."),
    NOT_ACCEPTABLE(
            "not-acceptable",
            HttpStatus.NOT_ACCEPTABLE,
            "Not acceptable",
            "The requested response media type is not supported."),
    ALREADY_MEMBER(
            "already-member",
            HttpStatus.CONFLICT,
            "Already a member",
            "The user is already a member of this workspace."),
    EMAIL_ALREADY_VERIFIED(
            "email-already-verified",
            HttpStatus.CONFLICT,
            "E-mail already verified",
            "The e-mail address is already verified."),
    LAST_OWNER("last-owner", HttpStatus.CONFLICT, "Last owner", "The workspace must keep at least one owner."),
    VERSION_CONFLICT(
            "version-conflict",
            HttpStatus.CONFLICT,
            "Version conflict",
            "The resource was modified by another request. Reload it and try again."),
    INVITATION_NOT_PENDING(
            "invitation-not-pending",
            HttpStatus.CONFLICT,
            "Invitation not pending",
            "The invitation is no longer pending."),
    DELIVERY_NOT_RETRYABLE(
            "delivery-not-retryable",
            HttpStatus.CONFLICT,
            "Delivery not retryable",
            "Only dead deliveries can be retried."),
    PAYLOAD_TOO_LARGE(
            "payload-too-large",
            HttpStatus.CONTENT_TOO_LARGE,
            "Payload too large",
            "The request body exceeds the maximum allowed size."),
    UNSUPPORTED_MEDIA_TYPE(
            "unsupported-media-type",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported media type",
            "The request content type is not supported."),
    RATE_LIMITED("rate-limited", HttpStatus.TOO_MANY_REQUESTS, "Too many requests", "Too many requests. Retry later."),
    INTERNAL_ERROR(
            "internal-error",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal error",
            "An unexpected error occurred. Quote the traceId when reporting it."),
    SERVICE_UNAVAILABLE(
            "service-unavailable",
            HttpStatus.SERVICE_UNAVAILABLE,
            "Service unavailable",
            "The service is temporarily unavailable. Retry later.");

    /** Prefix of every problem type URI. */
    public static final String URI_PREFIX = "urn:warden:problem:";

    private final String slug;
    private final HttpStatus status;
    private final String title;
    private final String defaultDetail;
    private final URI uri;

    ProblemType(String slug, HttpStatus status, String title, String defaultDetail) {
        this.slug = slug;
        this.status = status;
        this.title = title;
        this.defaultDetail = defaultDetail;
        this.uri = URI.create(URI_PREFIX + slug);
    }

    public String slug() {
        return slug;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String defaultDetail() {
        return defaultDetail;
    }

    public URI uri() {
        return uri;
    }

    public static Optional<ProblemType> fromSlug(String slug) {
        return Arrays.stream(values()).filter(type -> type.slug.equals(slug)).findFirst();
    }
}
