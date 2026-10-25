package io.github.kaulinmindiola.warden.iam.application;

/**
 * Tokens issued by a successful login.
 *
 * @param accessToken signed ES256 access token
 * @param accessExpiresIn seconds until the access token expires
 * @param refreshToken refresh token, {@code wrt_} prefixed
 * @param refreshExpiresIn seconds until the refresh token expires
 */
public record LoginResult(String accessToken, long accessExpiresIn, String refreshToken, long refreshExpiresIn) {

    /** The tokens are left out so a result never leaks into logs. */
    @Override
    public String toString() {
        return "LoginResult[tokens redacted, accessExpiresIn=" + accessExpiresIn + ", refreshExpiresIn="
                + refreshExpiresIn + "]";
    }
}
