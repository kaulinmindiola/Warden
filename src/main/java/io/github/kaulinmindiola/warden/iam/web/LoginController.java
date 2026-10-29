package io.github.kaulinmindiola.warden.iam.web;

import io.github.kaulinmindiola.warden.iam.application.InvalidCredentialsException;
import io.github.kaulinmindiola.warden.iam.application.LoginResult;
import io.github.kaulinmindiola.warden.iam.application.LoginService;
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

/**
 * Login (UC-03). Public endpoint; served under {@code /api/v1}.
 *
 * <p>The client IP is the address of the TCP peer ({@code getRemoteAddr()}). {@code
 * X-Forwarded-For} is ignored: the reference environment has no proxy, so any client could forge
 * it to escape the per-IP limits (ADR-014).
 */
@RestController
@RequestMapping("/auth")
class LoginController {

    private final LoginService loginService;

    LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    /**
     * Starts a session. Responds 200 with the tokens and {@code Cache-Control: no-store}; 401
     * {@code invalid-credentials} with the same body whatever was wrong (REQ-FUNC-009); 429 {@code
     * rate-limited} with {@code Retry-After} when the e-mail or the IP is over its limit
     * (REQ-SEC-006, REQ-SEC-007).
     */
    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(
            @RequestBody LoginRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            HttpServletRequest httpRequest) {
        LoginResult result;
        try {
            result = loginService.login(request.email(), request.password(), userAgent, httpRequest.getRemoteAddr());
        } catch (InvalidCredentialsException invalid) {
            throw new ProblemException(ProblemType.INVALID_CREDENTIALS);
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
