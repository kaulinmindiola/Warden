package io.github.kaulinmindiola.warden.iam.web;

import io.github.kaulinmindiola.warden.iam.application.InvalidRegistrationException;
import io.github.kaulinmindiola.warden.iam.application.RegistrationService;
import io.github.kaulinmindiola.warden.shared.web.FieldViolation;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account registration (UC-01). Public endpoint; served under {@code /api/v1}. */
@RestController
@RequestMapping("/auth")
class RegistrationController {

    private final RegistrationService registrationService;

    RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    /**
     * Registers an account. Responds 202 with no body whether or not the e-mail already had an
     * account (REQ-FUNC-001, REQ-FUNC-002), and 400 {@code validation-error} naming each invalid
     * field.
     */
    @PostMapping("/register")
    ResponseEntity<Void> register(@RequestBody RegisterRequest request) {
        try {
            registrationService.register(request.email(), request.password(), request.locale());
        } catch (InvalidRegistrationException invalid) {
            List<FieldViolation> violations = invalid.violations().entrySet().stream()
                    .map(violation -> new FieldViolation(violation.getKey(), violation.getValue()))
                    .toList();
            throw new ProblemException(ProblemType.VALIDATION_ERROR, violations);
        }
        return ResponseEntity.accepted().build();
    }
}
