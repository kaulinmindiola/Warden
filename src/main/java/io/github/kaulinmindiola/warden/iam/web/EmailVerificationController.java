package io.github.kaulinmindiola.warden.iam.web;

import io.github.kaulinmindiola.warden.iam.application.EmailAlreadyVerifiedException;
import io.github.kaulinmindiola.warden.iam.application.EmailVerificationService;
import io.github.kaulinmindiola.warden.iam.domain.InvalidOneTimeTokenException;
import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E-mail verification (UC-02); served under {@code /api/v1}. */
@RestController
@RequestMapping("/auth/email")
class EmailVerificationController {

    private final EmailVerificationService verificationService;

    EmailVerificationController(EmailVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * Public. Verifies the e-mail address the token was sent to. Responds 204, or 400 {@code
     * invalid-one-time-token} with an identical body whatever the reason (REQ-FUNC-006).
     */
    @PostMapping("/verify")
    ResponseEntity<Void> verify(@RequestBody VerifyEmailRequest request) {
        try {
            verificationService.verify(request.token());
        } catch (InvalidOneTimeTokenException invalid) {
            throw new ProblemException(ProblemType.INVALID_ONE_TIME_TOKEN);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Authenticated. Sends a new verification link and invalidates the previous ones. Responds
     * 202, or 409 {@code email-already-verified} (REQ-FUNC-007).
     */
    @PostMapping("/verify/resend")
    ResponseEntity<Void> resend(@AuthenticationPrincipal CurrentUser user) {
        try {
            verificationService.resend(user.userId());
        } catch (EmailAlreadyVerifiedException alreadyVerified) {
            throw new ProblemException(ProblemType.EMAIL_ALREADY_VERIFIED);
        }
        return ResponseEntity.accepted().build();
    }
}
