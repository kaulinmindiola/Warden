package io.github.kaulinmindiola.warden.iam.web;

/**
 * Body of {@code POST /api/v1/auth/password/reset}.
 *
 * @param token the one-time token from the reset link
 * @param newPassword the new password
 */
public record ResetPasswordRequest(String token, String newPassword) {

    /** The token and the password are left out so a request never leaks into logs. */
    @Override
    public String toString() {
        return "ResetPasswordRequest[token redacted, newPassword redacted]";
    }
}
