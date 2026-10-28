package io.github.kaulinmindiola.warden.workspace.web;

/**
 * Body of {@code POST /api/v1/invitations/accept}.
 *
 * @param token the one-time token from the invitation link
 */
public record AcceptInvitationRequest(String token) {

    /** The token is left out so a request never leaks into logs. */
    @Override
    public String toString() {
        return "AcceptInvitationRequest[token redacted]";
    }
}
