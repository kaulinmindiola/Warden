package io.github.kaulinmindiola.warden.iam.web;

import io.github.kaulinmindiola.warden.iam.application.InvalidPasswordResetException;
import io.github.kaulinmindiola.warden.iam.application.PasswordResetService;
import io.github.kaulinmindiola.warden.iam.domain.InvalidOneTimeTokenException;
import io.github.kaulinmindiola.warden.shared.web.FieldViolation;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Password recovery (UC-08, UC-09). Public endpoints; served under {@code /api/v1}. */
@RestController
@RequestMapping("/auth/password")
class PasswordResetController {

    private final PasswordResetService passwordResetService;

    PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /**
     * Requests a reset link. Responds 202 with no body whether or not the e-mail has an account
     * (REQ-FUNC-019), and 400 {@code validation-error} if the e-mail is missing or malformed.
     */
    @PostMapping("/forgot")
    ResponseEntity<Void> forgot(@RequestBody ForgotPasswordRequest request) {
        try {
            passwordResetService.requestReset(request.email());
        } catch (InvalidPasswordResetException invalid) {
            throw validationError(invalid);
        }
        return ResponseEntity.accepted().build();
    }

    /**
     * Replaces the password with a reset link. Responds 204; 400 {@code validation-error} if the
     * new password breaks the policy; 400 {@code invalid-one-time-token} with an identical body
     * whatever is wrong with the token (REQ-FUNC-020, REQ-FUNC-021).
     */
    @PostMapping("/reset")
    ResponseEntity<Void> reset(@RequestBody ResetPasswordRequest request) {
        try {
            passwordResetService.reset(request.token(), request.newPassword());
        } catch (InvalidPasswordResetException invalid) {
            throw validationError(invalid);
        } catch (InvalidOneTimeTokenException invalid) {
            throw new ProblemException(ProblemType.INVALID_ONE_TIME_TOKEN);
        }
        return ResponseEntity.noContent().build();
    }

    private static ProblemException validationError(InvalidPasswordResetException invalid) {
        List<FieldViolation> violations = invalid.violations().entrySet().stream()
                .map(violation -> new FieldViolation(violation.getKey(), violation.getValue()))
                .toList();
        return new ProblemException(ProblemType.VALIDATION_ERROR, violations);
    }
}
