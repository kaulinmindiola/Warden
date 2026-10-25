package io.github.kaulinmindiola.warden.iam.web;

import io.github.kaulinmindiola.warden.iam.application.InvalidCredentialsException;
import io.github.kaulinmindiola.warden.iam.application.LoginResult;
import io.github.kaulinmindiola.warden.iam.application.LoginService;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login (UC-03). Public endpoint; served under {@code /api/v1}. */
@RestController
@RequestMapping("/auth")
class LoginController {

    private final LoginService loginService;

    LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    /**
     * Starts a session. Responds 200 with the tokens and {@code Cache-Control: no-store}, or 401
     * {@code invalid-credentials} with the same body whatever was wrong (REQ-FUNC-009).
     */
    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(
            @RequestBody LoginRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        LoginResult result;
        try {
            result = loginService.login(request.email(), request.password(), userAgent);
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
