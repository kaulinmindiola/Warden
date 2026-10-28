package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.CurrentUser;
import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.workspace.application.AlreadyMemberException;
import io.github.kaulinmindiola.warden.workspace.application.EmailNotVerifiedException;
import io.github.kaulinmindiola.warden.workspace.application.InvalidInvitationTokenException;
import io.github.kaulinmindiola.warden.workspace.application.InvitationAcceptanceService;
import io.github.kaulinmindiola.warden.workspace.application.InvitationEmailMismatchException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acceptance of invitations (UC-14). Authenticated but not workspace-scoped: the caller is not a
 * member yet. Served under {@code /api/v1}.
 */
@RestController
@RequestMapping("/invitations")
class InvitationAcceptanceController {

    private final InvitationAcceptanceService acceptanceService;

    InvitationAcceptanceController(InvitationAcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    /**
     * Joins the invited workspace: 200 {@code {workspaceId, role}}; 400 {@code
     * invalid-one-time-token} with an identical body whatever the reason; 403 {@code
     * email-not-verified} or {@code invitation-email-mismatch} without consuming the token; 409
     * {@code already-member} (REQ-FUNC-036).
     */
    @PostMapping("/accept")
    AcceptedInvitationResponse accept(
            @AuthenticationPrincipal CurrentUser user, @RequestBody AcceptInvitationRequest request) {
        try {
            return AcceptedInvitationResponse.from(acceptanceService.accept(user.userId(), request.token()));
        } catch (InvalidInvitationTokenException invalid) {
            throw new ProblemException(ProblemType.INVALID_ONE_TIME_TOKEN);
        } catch (EmailNotVerifiedException notVerified) {
            throw new ProblemException(ProblemType.EMAIL_NOT_VERIFIED);
        } catch (InvitationEmailMismatchException mismatch) {
            throw new ProblemException(ProblemType.INVITATION_EMAIL_MISMATCH);
        } catch (AlreadyMemberException alreadyMember) {
            throw new ProblemException(ProblemType.ALREADY_MEMBER);
        }
    }
}
