package io.github.kaulinmindiola.warden.iam.web;

import io.github.kaulinmindiola.warden.iam.application.EmailVerificationService;
import io.github.kaulinmindiola.warden.iam.domain.InvalidOneTimeTokenException;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E-mail verification (UC-02). Public endpoint; served under {@code /api/v1}. */
@RestController
@RequestMapping("/auth/email")
class EmailVerificationController {

    private final EmailVerificationService verificationService;

    EmailVerificationController(EmailVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /**
     * Verifies the e-mail address the token was sent to. Responds 204, or 400 {@code
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
}
