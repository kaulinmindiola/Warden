package io.github.kaulinmindiola.warden.notification.web;

import io.github.kaulinmindiola.warden.notification.application.InvalidPreferencesException;
import io.github.kaulinmindiola.warden.notification.application.NotificationPreferenceService;
import io.github.kaulinmindiola.warden.notification.application.PreferenceChange;
import io.github.kaulinmindiola.warden.notification.application.PreferenceNotOptionalException;
import io.github.kaulinmindiola.warden.notification.application.PreferenceWorkspaceNotFoundException;
import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.security.MembershipUnavailableException;
import io.github.kaulinmindiola.warden.shared.web.FieldViolation;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's notification language and preferences (UC-26). Authenticated; under {@code /api/v1}. */
@RestController
@RequestMapping("/me/notification-preferences")
class NotificationPreferenceController {

    private final NotificationPreferenceService preferences;

    NotificationPreferenceController(NotificationPreferenceService preferences) {
        this.preferences = preferences;
    }

    /** The language and the optional notifications of every workspace of the caller (REQ-FUNC-050). */
    @GetMapping
    NotificationPreferencesResponse get(@AuthenticationPrincipal CurrentUser user) {
        try {
            return NotificationPreferencesResponse.from(preferences.get(user.userId()));
        } catch (MembershipUnavailableException unavailable) {
            throw ProblemException.serviceUnavailable();
        }
    }

    /**
     * Changes the language and the listed preferences: 200 with the result; 400 {@code
     * validation-error}; 400 {@code preference-not-optional} when disabling a SECURITY or
     * TRANSACTIONAL notification; 404 for a workspace the caller is not a member of (REQ-FUNC-049,
     * 051). Nothing is changed when the answer is not 200.
     */
    @PutMapping
    NotificationPreferencesResponse update(
            @AuthenticationPrincipal CurrentUser user, @RequestBody UpdateNotificationPreferencesRequest request) {
        List<PreferenceChange> changes = request.preferences() == null
                ? List.of()
                : request.preferences().stream()
                        .map(item -> item == null
                                ? null
                                : new PreferenceChange(item.key(), item.workspaceId(), item.enabled()))
                        .toList();
        try {
            return NotificationPreferencesResponse.from(preferences.update(user.userId(), request.locale(), changes));
        } catch (InvalidPreferencesException invalid) {
            List<FieldViolation> violations = invalid.violations().entrySet().stream()
                    .map(violation -> new FieldViolation(violation.getKey(), violation.getValue()))
                    .toList();
            throw new ProblemException(ProblemType.VALIDATION_ERROR, violations);
        } catch (PreferenceNotOptionalException notOptional) {
            throw new ProblemException(ProblemType.PREFERENCE_NOT_OPTIONAL);
        } catch (PreferenceWorkspaceNotFoundException notFound) {
            throw new ProblemException(ProblemType.NOT_FOUND);
        } catch (MembershipUnavailableException unavailable) {
            throw ProblemException.serviceUnavailable();
        }
    }
}
