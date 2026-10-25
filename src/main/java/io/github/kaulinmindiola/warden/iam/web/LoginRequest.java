package io.github.kaulinmindiola.warden.iam.web;

/**
 * Body of {@code POST /api/v1/auth/login}.
 *
 * @param email e-mail address
 * @param password password
 */
public record LoginRequest(String email, String password) {

    /** Credentials are left out so a request never leaks into logs. */
    @Override
    public String toString() {
        return "LoginRequest[email redacted, password redacted]";
    }
}
