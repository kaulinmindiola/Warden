package io.github.kaulinmindiola.warden.iam.infrastructure;

import java.time.Instant;

/**
 * A signed access token.
 *
 * @param value the compact JWT
 * @param expiresAt value of the {@code exp} claim
 * @param expiresInSeconds lifetime in seconds, as returned in {@code expires_in}
 */
public record IssuedAccessToken(String value, Instant expiresAt, long expiresInSeconds) {

    /** The token value is left out so it never leaks into logs. */
    @Override
    public String toString() {
        return "IssuedAccessToken[value redacted, expiresAt=" + expiresAt + "]";
    }
}
