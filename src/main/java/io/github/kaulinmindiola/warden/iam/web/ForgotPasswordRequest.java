package io.github.kaulinmindiola.warden.iam.web;

/**
 * Body of {@code POST /api/v1/auth/password/forgot}.
 *
 * @param email e-mail address of the account
 */
public record ForgotPasswordRequest(String email) {

    /** The e-mail is left out so a request never leaks into logs. */
    @Override
    public String toString() {
        return "ForgotPasswordRequest[email redacted]";
    }
}
