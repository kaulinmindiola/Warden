package io.github.kaulinmindiola.warden.workspace.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/**
 * REQ-FUNC-033: at most 50 invitations per workspace and 100 per inviter, each per day, with the
 * SRS values of the shared test context. Only invitations that would be sent spend the quotas.
 */
class InvitationRateLimitIT extends IntegrationTest {

    private static final int PER_WORKSPACE = 50;
    private static final int PER_INVITER = 100;
    private static final Duration DAY = Duration.ofDays(1);

    @Autowired
    private WebApplicationContext context;

    private IamTestClient iam;
    private String ownerEmail;
    private String ownerToken;

    @BeforeEach
    void createOwner() throws Exception {
        iam = new IamTestClient(context);
        ownerEmail = iam.registerVerifiedAccount();
        ownerToken = iam.login(ownerEmail);
    }

    @Test
    @Verifies("REQ-FUNC-033")
    void theFiftyFirstInvitationOfTheDayInAWorkspaceIsRejected() throws Exception {
        UUID workspace = createWorkspace();
        inviteMany(ownerToken, workspace, PER_WORKSPACE);

        expectRateLimited(invite(ownerToken, workspace, newEmail()));
        assertThat(invitationsIn(workspace)).isEqualTo(PER_WORKSPACE);

        clock.advance(DAY.plusSeconds(1));
        ownerToken = iam.login(ownerEmail);
        invite(ownerToken, workspace, newEmail()).andExpect(status().isCreated());
    }

    @Test
    @Verifies("REQ-FUNC-033")
    void anInviterIsLimitedAcrossAllWorkspaces() throws Exception {
        UUID first = createWorkspace();
        UUID second = createWorkspace();
        UUID third = createWorkspace();
        inviteMany(ownerToken, first, 40);
        inviteMany(ownerToken, second, 40);
        inviteMany(ownerToken, third, PER_INVITER - 80);

        expectRateLimited(invite(ownerToken, third, newEmail()));
        assertThat(invitationsIn(third)).isEqualTo(PER_INVITER - 80);
    }

    @Test
    @Verifies("REQ-FUNC-033")
    void theWorkspaceQuotaIsSharedByAllItsAdmins() throws Exception {
        UUID workspace = createWorkspace();
        String adminToken = seedAdmin(workspace);
        inviteMany(ownerToken, workspace, 30);
        inviteMany(adminToken, workspace, PER_WORKSPACE - 30);

        expectRateLimited(invite(adminToken, workspace, newEmail()));
    }

    @Test
    @Verifies("REQ-FUNC-033")
    void rejectedRequestsDoNotSpendTheQuota() throws Exception {
        UUID workspace = createWorkspace();
        String adminEmail = iam.registerNewAccount();
        seedMember(workspace, adminEmail, "ADMIN");
        for (int request = 1; request <= 5; request++) {
            iam.postJson(invitations(workspace), Map.of("email", newEmail(), "role", "OWNER"), ownerToken)
                    .andExpect(status().isBadRequest());
            invite(ownerToken, workspace, adminEmail).andExpect(status().isConflict());
        }

        inviteMany(ownerToken, workspace, PER_WORKSPACE);

        expectRateLimited(invite(ownerToken, workspace, newEmail()));
    }

    private void inviteMany(String token, UUID workspace, int count) throws Exception {
        for (int invitation = 1; invitation <= count; invitation++) {
            invite(token, workspace, newEmail()).andExpect(status().isCreated());
        }
    }

    private static void expectRateLimited(ResultActions result) throws Exception {
        MvcResult rejected = result.andExpect(status().isTooManyRequests())
                .andExpect(
                        jsonPath("$.type").value(ProblemType.RATE_LIMITED.uri().toString()))
                .andReturn();
        String retryAfter = rejected.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).as("Retry-After").isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, DAY.toSeconds());
    }

    private ResultActions invite(String token, UUID workspace, String email) throws Exception {
        return iam.postJson(invitations(workspace), Map.of("email", email, "role", "MEMBER"), token);
    }

    private UUID createWorkspace() throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", "Quota"), ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** A new ADMIN of the workspace, seeded as the superuser, and its access token. */
    private String seedAdmin(UUID workspace) throws Exception {
        String email = iam.registerNewAccount();
        seedMember(workspace, email, "ADMIN");
        return iam.login(email);
    }

    private void seedMember(UUID workspace, String email, String role) {
        superuser()
                .sql("INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, ?, now())")
                .param(workspace)
                .param(iam.userIdOf(email))
                .param(role)
                .update();
    }

    private long invitationsIn(UUID workspace) {
        return superuser()
                .sql("SELECT count(*) FROM invitation WHERE workspace_id = ?")
                .param(workspace)
                .query(Long.class)
                .single();
    }

    private static String invitations(UUID workspace) {
        return API + "/workspaces/" + workspace + "/invitations";
    }

    private static String newEmail() {
        return "quota-" + UUID.randomUUID() + "@mail.com";
    }

    /** Only for seeding and inspection: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
