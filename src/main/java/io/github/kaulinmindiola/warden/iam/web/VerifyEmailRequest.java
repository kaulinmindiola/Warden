package io.github.kaulinmindiola.warden.iam.web;

/**
 * Body of {@code POST /api/v1/auth/email/verify}.
 *
 * @param token the one-time token from the verification link
 */
public record VerifyEmailRequest(String token) {

    /** The token is left out so a request never leaks into logs. */
    @Override
    public String toString() {
        return "VerifyEmailRequest[token redacted]";
    }
}
