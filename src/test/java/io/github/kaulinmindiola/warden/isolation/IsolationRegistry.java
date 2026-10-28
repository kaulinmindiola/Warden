package io.github.kaulinmindiola.warden.isolation;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Every workspace-scoped endpoint, with what the isolation suite and the role matrix need to call
 * it (SDD R7, REQ-MAINT-003). {@link WorkspaceEndpointRulesIT} fails the build when a handler under
 * {@value #WORKSPACE_ROUTE} is missing here.
 *
 * <p>To add an endpoint: one entry below, with the role its handler declares.
 */
public final class IsolationRegistry {

    /** Prefix of every workspace-scoped route; the variable name is the interceptor's contract. */
    public static final String WORKSPACE_ROUTE = ApiWebConfiguration.API_PREFIX + "/workspaces/{workspaceId}";

    /** Any route below {@code /workspaces/} with a path variable is workspace-scoped. */
    public static final String WORKSPACE_SCOPED_PREFIX = ApiWebConfiguration.API_PREFIX + "/workspaces/{";

    private static final String LEAVE = WORKSPACE_ROUTE + "/leave";
    private static final String MEMBERS = WORKSPACE_ROUTE + "/members";
    private static final String MEMBER = MEMBERS + "/{userId}";
    private static final String MEMBER_ROLE = MEMBER + "/role";
    private static final String INVITATIONS = WORKSPACE_ROUTE + "/invitations";
    private static final String INVITATION = INVITATIONS + "/{invitationId}";
    private static final String NOTES = WORKSPACE_ROUTE + "/notes";
    private static final String NOTE = NOTES + "/{noteId}";
    private static final String NEW_NOTE = "{\"title\":\"Registry note\",\"body\":\"Body\"}";
    private static final String EDITED_NOTE = "{\"title\":\"Edited\",\"body\":\"Body\",\"version\":0}";
    private static final String DEMOTE_TO_VIEWER = "{\"role\":\"VIEWER\"}";

    public static final List<WorkspaceEndpoint> ENDPOINTS = List.of(
            new WorkspaceEndpoint(
                    HttpMethod.GET,
                    WORKSPACE_ROUTE,
                    Role.VIEWER,
                    200,
                    target -> MockMvcRequestBuilders.get(WORKSPACE_ROUTE, target.workspaceId())),
            new WorkspaceEndpoint(
                    HttpMethod.DELETE,
                    WORKSPACE_ROUTE,
                    Role.OWNER,
                    204,
                    target -> MockMvcRequestBuilders.delete(WORKSPACE_ROUTE, target.workspaceId())),
            new WorkspaceEndpoint(
                    HttpMethod.POST,
                    LEAVE,
                    Role.VIEWER,
                    204,
                    target -> MockMvcRequestBuilders.post(LEAVE, target.workspaceId())),
            new WorkspaceEndpoint(
                    HttpMethod.GET,
                    MEMBERS,
                    Role.VIEWER,
                    200,
                    target -> MockMvcRequestBuilders.get(MEMBERS, target.workspaceId())),
            new WorkspaceEndpoint(
                    HttpMethod.PUT,
                    MEMBER_ROLE,
                    Role.ADMIN,
                    200,
                    target -> MockMvcRequestBuilders.put(MEMBER_ROLE, target.workspaceId(), target.newMember())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(DEMOTE_TO_VIEWER)),
            new WorkspaceEndpoint(
                    HttpMethod.DELETE,
                    MEMBER,
                    Role.ADMIN,
                    204,
                    target -> MockMvcRequestBuilders.delete(MEMBER, target.workspaceId(), target.newMember())),
            new WorkspaceEndpoint(
                    HttpMethod.POST,
                    INVITATIONS,
                    Role.ADMIN,
                    201,
                    target -> MockMvcRequestBuilders.post(INVITATIONS, target.workspaceId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"invited-" + UUID.randomUUID() + "@mail.com\",\"role\":\"MEMBER\"}")),
            new WorkspaceEndpoint(
                    HttpMethod.GET,
                    INVITATIONS,
                    Role.ADMIN,
                    200,
                    target -> MockMvcRequestBuilders.get(INVITATIONS, target.workspaceId())),
            new WorkspaceEndpoint(
                    HttpMethod.DELETE,
                    INVITATION,
                    Role.ADMIN,
                    204,
                    target -> MockMvcRequestBuilders.delete(INVITATION, target.workspaceId(), target.newInvitation())),
            new WorkspaceEndpoint(
                    HttpMethod.GET,
                    NOTES,
                    Role.VIEWER,
                    200,
                    target -> MockMvcRequestBuilders.get(NOTES, target.workspaceId())),
            new WorkspaceEndpoint(
                    HttpMethod.POST,
                    NOTES,
                    Role.MEMBER,
                    201,
                    target -> MockMvcRequestBuilders.post(NOTES, target.workspaceId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(NEW_NOTE)),
            new WorkspaceEndpoint(
                    HttpMethod.GET,
                    NOTE,
                    Role.VIEWER,
                    200,
                    target -> MockMvcRequestBuilders.get(NOTE, target.workspaceId(), target.newNote())),
            new WorkspaceEndpoint(
                    HttpMethod.PUT,
                    NOTE,
                    Role.MEMBER,
                    200,
                    target -> MockMvcRequestBuilders.put(NOTE, target.workspaceId(), target.newNote())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(EDITED_NOTE)),
            new WorkspaceEndpoint(
                    HttpMethod.DELETE,
                    NOTE,
                    Role.MEMBER,
                    204,
                    target -> MockMvcRequestBuilders.delete(NOTE, target.workspaceId(), target.newNote())));

    private IsolationRegistry() {}

    public static Optional<WorkspaceEndpoint> find(String key) {
        return ENDPOINTS.stream().filter(endpoint -> endpoint.key().equals(key)).findFirst();
    }
}
