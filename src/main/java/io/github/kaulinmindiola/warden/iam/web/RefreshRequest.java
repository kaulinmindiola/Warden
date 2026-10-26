package io.github.kaulinmindiola.warden.iam.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /api/v1/auth/refresh}, in snake_case like the token response.
 *
 * @param refreshToken the refresh token, {@code wrt_} prefixed
 */
public record RefreshRequest(@JsonProperty("refresh_token") String refreshToken) {

    /** The token is left out so a request never leaks into logs. */
    @Override
    public String toString() {
        return "RefreshRequest[refreshToken redacted]";
    }
}
