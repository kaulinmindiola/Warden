package io.github.kaulinmindiola.warden.workspace.web;

/**
 * Body of {@code POST /api/v1/workspaces/{workspaceId}/invitations}. Validated by the service.
 *
 * @param email the address to invite
 * @param role {@code ADMIN}, {@code MEMBER} or {@code VIEWER}
 * @param locale optional, {@code es} or {@code en}
 */
public record CreateInvitationRequest(String email, String role, String locale) {

    /** The e-mail is left out so a request never leaks into logs. */
    @Override
    public String toString() {
        return "CreateInvitationRequest[email redacted, role=" + role + ", locale=" + locale + "]";
    }
}
