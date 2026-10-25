package io.github.kaulinmindiola.warden.iam.web;

/**
 * Body of {@code POST /api/v1/auth/register}. Validation happens in the registration service so
 * every invalid field is reported together.
 *
 * @param email e-mail address
 * @param password password
 * @param locale optional, {@code es} or {@code en}; {@code es} when omitted
 */
public record RegisterRequest(String email, String password, String locale) {

    /** The password is left out so a request never leaks into logs. */
    @Override
    public String toString() {
        return "RegisterRequest[email redacted, password redacted, locale=" + locale + "]";
    }
}
