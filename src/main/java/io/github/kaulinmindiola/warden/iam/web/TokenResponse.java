package io.github.kaulinmindiola.warden.iam.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token response of login and refresh (REQ-INT-003, SDD V08), with snake_case names.
 *
 * @param accessToken signed ES256 access token
 * @param tokenType always {@code Bearer}
 * @param expiresIn seconds until the access token expires
 * @param refreshToken refresh token, {@code wrt_} prefixed
 * @param refreshExpiresIn seconds until the refresh token expires
 */
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("refresh_expires_in") long refreshExpiresIn) {

    /** Value of {@code token_type} (RFC 6750). */
    public static final String BEARER = "Bearer";

    /** The tokens are left out so a response never leaks into logs. */
    @Override
    public String toString() {
        return "TokenResponse[tokens redacted, expiresIn=" + expiresIn + ", refreshExpiresIn=" + refreshExpiresIn + "]";
    }
}
