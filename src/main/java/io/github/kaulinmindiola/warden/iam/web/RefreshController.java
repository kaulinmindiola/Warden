package io.github.kaulinmindiola.warden.iam.web;

import io.github.kaulinmindiola.warden.iam.application.InvalidRefreshTokenException;
import io.github.kaulinmindiola.warden.iam.application.LoginResult;
import io.github.kaulinmindiola.warden.iam.application.RefreshService;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Refresh-token rotation (UC-04). Public endpoint; served under {@code /api/v1}. */
@RestController
@RequestMapping("/auth")
class RefreshController {

    static final String HEADER_NOT_ALLOWED =
            "The refresh token must be sent in the request body, not in the Authorization header.";

    private final RefreshService refreshService;

    RefreshController(RefreshService refreshService) {
        this.refreshService = refreshService;
    }

    /**
     * Rotates the refresh token. Responds 200 with new tokens and {@code Cache-Control:
     * no-store}; 400 {@code malformed-request} if an {@code Authorization} header is sent
     * (REQ-INT-002); 401 {@code invalid-refresh-token} with the same body whatever was wrong; 429
     * {@code rate-limited} with {@code Retry-After} when the client IP is over its limit
     * (REQ-SEC-009). The IP is the TCP peer; {@code X-Forwarded-For} is ignored (ADR-014).
     */
    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(
            @RequestBody RefreshRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest httpRequest) {
        if (authorization != null) {
            throw new ProblemException(ProblemType.MALFORMED_REQUEST, HEADER_NOT_ALLOWED);
        }
        LoginResult result;
        try {
            result = refreshService.refresh(request.refreshToken(), httpRequest.getRemoteAddr());
        } catch (InvalidRefreshTokenException invalid) {
            throw new ProblemException(ProblemType.INVALID_REFRESH_TOKEN);
        }
        TokenResponse body = new TokenResponse(
                result.accessToken(),
                TokenResponse.BEARER,
                result.accessExpiresIn(),
                result.refreshToken(),
                result.refreshExpiresIn());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
