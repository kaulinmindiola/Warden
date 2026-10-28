package io.github.kaulinmindiola.warden.isolation;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The workspace under test, another workspace, and the callers of the isolation suite and the role
 * matrix. Memberships, notes and invitations are seeded as the superuser, which bypasses Row Level
 * Security.
 *
 * @param ownerId the OWNER of both workspaces and author of seeded resources
 * @param ownerToken an access token of that OWNER
 * @param workspace the workspace the requests target
 * @param otherWorkspace a second workspace of the same owner
 * @param outsiderToken a user who belongs to no workspace
 * @param otherMemberToken an OWNER of the other workspace, not a member of {@code workspace}
 */
record WorkspaceFixture(
        IamTestClient iam,
        JdbcClient superuser,
        UUID ownerId,
        String ownerToken,
        UUID workspace,
        UUID otherWorkspace,
        String outsiderToken,
        String otherMemberToken) {

    private static final SecureRandom RANDOM = new SecureRandom();

    static WorkspaceFixture create(IamTestClient iam, JdbcClient superuser) throws Exception {
        String ownerEmail = iam.registerVerifiedAccount();
        String ownerToken = iam.login(ownerEmail);
        UUID workspace = createWorkspace(iam, ownerToken, "Isolated");
        UUID otherWorkspace = createWorkspace(iam, ownerToken, "Other");
        String outsiderToken = iam.login(iam.registerNewAccount());
        String otherMemberEmail = iam.registerNewAccount();
        addMember(superuser, otherWorkspace, iam.userIdOf(otherMemberEmail), Role.OWNER);
        return new WorkspaceFixture(
                iam,
                superuser,
                iam.userIdOf(ownerEmail),
                ownerToken,
                workspace,
                otherWorkspace,
                outsiderToken,
                iam.login(otherMemberEmail));
    }

    /** A target in the given workspace; resources are seeded only in the fixture's workspaces. */
    WorkspaceEndpoint.Target target(UUID workspaceId) {
        boolean known = workspaceId.equals(workspace) || workspaceId.equals(otherWorkspace);
        return new WorkspaceEndpoint.Target() {
            @Override
            public UUID workspaceId() {
                return workspaceId;
            }

            @Override
            public UUID newNote() {
                return known ? seedNote(workspaceId) : UUID.randomUUID();
            }

            @Override
            public UUID newMember() {
                return known ? seedMember(workspaceId) : UUID.randomUUID();
            }

            @Override
            public UUID newInvitation() {
                return known ? seedInvitation(workspaceId) : UUID.randomUUID();
            }
        };
    }

    /** A new user with the given role in {@link #workspace()}, logged in. */
    String tokenWithRole(Role role) throws Exception {
        String email = iam.registerNewAccount();
        addMember(superuser, workspace, iam.userIdOf(email), role);
        return iam.login(email);
    }

    /** Deletes {@link #workspace()} through the API, as its OWNER (UC-19). */
    void deleteWorkspace() throws Exception {
        MvcResult result =
                perform(MockMvcRequestBuilders.delete(IsolationRegistry.WORKSPACE_ROUTE, workspace), ownerToken);
        if (result.getResponse().getStatus() != 204) {
            throw new IllegalStateException("Deleting the fixture workspace answered "
                    + result.getResponse().getStatus());
        }
    }

    MvcResult perform(MockHttpServletRequestBuilder request, String accessToken) throws Exception {
        return iam.mvc()
                .perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn();
    }

    private UUID seedNote(UUID workspaceId) {
        UUID noteId = UUID.randomUUID();
        superuser.sql("""
                        INSERT INTO note (id, workspace_id, title, body, author_id, version, created_at, updated_at)
                        VALUES (?, ?, 'Seeded', 'Body', ?, 0, now(), now())
                        """).param(noteId).param(workspaceId).param(ownerId).update();
        return noteId;
    }

    private UUID seedMember(UUID workspaceId) {
        try {
            UUID userId = iam.userIdOf(iam.registerNewAccount());
            addMember(superuser, workspaceId, userId, Role.MEMBER);
            return userId;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not seed a member", failure);
        }
    }

    /** A pending invitation that does not expire during the test, whatever the test clock says. */
    private UUID seedInvitation(UUID workspaceId) {
        UUID invitationId = UUID.randomUUID();
        byte[] tokenHash = new byte[32];
        RANDOM.nextBytes(tokenHash);
        superuser
                .sql("""
                        INSERT INTO invitation (id, workspace_id, email_normalized, role, locale, token_hash, status,
                                                invited_by, expires_at, created_at)
                        VALUES (?, ?, ?, 'MEMBER', 'es', ?, 'PENDING', ?, now() + interval '3650 days', now())
                        """)
                .param(invitationId)
                .param(workspaceId)
                .param("seeded-" + invitationId + "@mail.com")
                .param(tokenHash)
                .param(ownerId)
                .update();
        return invitationId;
    }

    private static UUID createWorkspace(IamTestClient iam, String accessToken, String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), accessToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static void addMember(JdbcClient superuser, UUID workspaceId, UUID userId, Role role) {
        superuser
                .sql("INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, ?, now())")
                .param(workspaceId)
                .param(userId)
                .param(role.name())
                .update();
    }
}
