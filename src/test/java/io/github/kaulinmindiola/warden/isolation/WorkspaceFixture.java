package io.github.kaulinmindiola.warden.isolation;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The workspace under test, another workspace, and the callers of the isolation suite and the role
 * matrix. Memberships are seeded as the superuser, which bypasses Row Level Security; the
 * endpoints that add members arrive in later steps.
 *
 * @param workspace the workspace the requests target
 * @param otherWorkspace a workspace the caller of {@code otherMemberToken} owns
 * @param outsiderToken a user who belongs to no workspace
 * @param otherMemberToken the OWNER of the other workspace, not a member of {@code workspace}
 */
record WorkspaceFixture(
        IamTestClient iam,
        JdbcClient superuser,
        UUID workspace,
        UUID otherWorkspace,
        String outsiderToken,
        String otherMemberToken) {

    static WorkspaceFixture create(IamTestClient iam, JdbcClient superuser) throws Exception {
        String ownerToken = iam.login(iam.registerVerifiedAccount());
        UUID workspace = createWorkspace(iam, ownerToken, "Isolated");
        UUID otherWorkspace = createWorkspace(iam, ownerToken, "Other");
        String outsiderToken = iam.login(iam.registerNewAccount());
        String otherMemberEmail = iam.registerNewAccount();
        addMember(superuser, otherWorkspace, iam.userIdOf(otherMemberEmail), Role.OWNER);
        return new WorkspaceFixture(
                iam, superuser, workspace, otherWorkspace, outsiderToken, iam.login(otherMemberEmail));
    }

    /** A new user with the given role in {@link #workspace()}, logged in. */
    String tokenWithRole(Role role) throws Exception {
        String email = iam.registerNewAccount();
        addMember(superuser, workspace, iam.userIdOf(email), role);
        return iam.login(email);
    }

    MvcResult perform(MockHttpServletRequestBuilder request, String accessToken) throws Exception {
        return iam.mvc()
                .perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn();
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
