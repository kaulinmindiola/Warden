package io.github.kaulinmindiola.warden;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.iam.infrastructure.IamMetrics;
import io.github.kaulinmindiola.warden.shared.security.WorkspaceAuthorizationInterceptor;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/**
 * Identity and access metrics (REQ-OBS-001, SDD V19): after each operation through the API, its
 * series grows by exactly the expected amount. Counters are read as differences, since the shared
 * context accumulates the operations of every other test.
 */
class AccessMetricsIT extends IntegrationTest {

    private static final String NEW_PASSWORD = "another correct horse battery";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MeterRegistry meterRegistry;

    private IamTestClient iam;

    @BeforeEach
    void setUp() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies("REQ-OBS-001")
    void loginsAreCountedBySuccessFailureAndRateLimit() throws Exception {
        String email = iam.registerNewAccount();
        double success = logins("success");
        double failure = logins("failure");
        double rateLimited = logins("rate_limited");
        double rejections = counter("warden.ratelimit.rejections", "policy", "login-failures-per-email");

        iam.login(email);
        for (int attempt = 0; attempt < 5; attempt++) {
            iam.postJson(API + "/auth/login", Map.of("email", email, "password", "not the password"), null)
                    .andExpect(status().isUnauthorized());
        }
        iam.postJson(API + "/auth/login", Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isTooManyRequests());

        assertThat(logins("success") - success).isEqualTo(1);
        assertThat(logins("failure") - failure).isEqualTo(5);
        assertThat(logins("rate_limited") - rateLimited).isEqualTo(1);
        assertThat(counter("warden.ratelimit.rejections", "policy", "login-failures-per-email") - rejections)
                .isEqualTo(1);
    }

    @Test
    @Verifies("REQ-OBS-001")
    void refreshesAreCountedBySuccessInvalidAndReuse() throws Exception {
        IamTestClient.Tokens tokens = iam.loginTokens(iam.registerNewAccount());
        double success = refreshes("success");
        double invalid = refreshes("invalid");
        double reuse = refreshes("reuse");
        double revokedForReuse = revoked("reuse");

        refresh(tokens.refreshToken()).andExpect(status().isOk());
        refresh("wrt_unknown").andExpect(status().isUnauthorized());
        refresh("not-a-refresh-token").andExpect(status().isUnauthorized());
        refresh(tokens.refreshToken()).andExpect(status().isUnauthorized());

        assertThat(refreshes("success") - success).isEqualTo(1);
        assertThat(refreshes("invalid") - invalid).isEqualTo(2);
        assertThat(refreshes("reuse") - reuse).isEqualTo(1);
        assertThat(revoked("reuse") - revokedForReuse).isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-OBS-001", "REQ-FUNC-015", "REQ-FUNC-017", "REQ-FUNC-020"})
    void revokedSessionsAreCountedByCause() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        String loggedOut = iam.login(email);
        String terminating = iam.login(email);
        String terminated = iam.login(email);
        iam.login(email);
        double logout = revoked("logout");
        double terminate = revoked("terminate");
        double passwordReset = revoked("password_reset");

        iam.postJson(API + "/auth/logout", Map.of(), loggedOut).andExpect(status().isNoContent());
        iam.postJson(API + "/auth/logout", Map.of(), loggedOut).andExpect(status().isUnauthorized());
        iam.delete(API + "/auth/sessions/" + IamTestClient.sessionIdOf(terminated), terminating)
                .andExpect(status().isNoContent());
        iam.postJson(API + "/auth/password/forgot", Map.of("email", email), null)
                .andExpect(status().isAccepted());
        iam.postJson(
                        API + "/auth/password/reset",
                        Map.of("token", iam.latestPasswordResetToken(userId), "newPassword", NEW_PASSWORD),
                        null)
                .andExpect(status().isNoContent());

        assertThat(revoked("logout") - logout).isEqualTo(1);
        assertThat(revoked("terminate") - terminate).isEqualTo(1);
        assertThat(revoked("password_reset") - passwordReset)
                .as("the two sessions still active")
                .isEqualTo(2);
    }

    @Test
    @Verifies("REQ-OBS-001")
    void authorizationDenialsAreCountedByStatus() throws Exception {
        String ownerToken = iam.login(iam.registerVerifiedAccount());
        UUID workspace = UUID.fromString(JsonPath.read(
                iam.postJson(API + "/workspaces", Map.of("name", "Metrics"), ownerToken)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        String outsider = iam.login(iam.registerNewAccount());
        String viewerEmail = iam.registerNewAccount();
        JdbcClient.create(superuserDataSource())
                .sql(
                        "INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, 'VIEWER', now())")
                .param(workspace)
                .param(iam.userIdOf(viewerEmail))
                .update();
        String viewer = iam.login(viewerEmail);
        double notFound = denials("404");
        double forbidden = denials("403");

        iam.get(API + "/workspaces/" + workspace, outsider).andExpect(status().isNotFound());
        iam.get(API + "/workspaces/" + workspace + "/invitations", viewer).andExpect(status().isForbidden());
        iam.get(API + "/workspaces/" + workspace, viewer).andExpect(status().isOk());

        assertThat(denials("404") - notFound).isEqualTo(1);
        assertThat(denials("403") - forbidden).isEqualTo(1);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return iam.postJson(API + "/auth/refresh", Map.of("refresh_token", refreshToken), null);
    }

    private double logins(String outcome) {
        return counter(IamMetrics.LOGINS, IamMetrics.OUTCOME_TAG, outcome);
    }

    private double refreshes(String outcome) {
        return counter(IamMetrics.REFRESHES, IamMetrics.OUTCOME_TAG, outcome);
    }

    private double revoked(String reason) {
        return counter(IamMetrics.SESSIONS_REVOKED, IamMetrics.REASON_TAG, reason);
    }

    private double denials(String status) {
        return counter(
                WorkspaceAuthorizationInterceptor.DENIALS_METRIC, WorkspaceAuthorizationInterceptor.STATUS_TAG, status);
    }

    private double counter(String name, String tag, String value) {
        return meterRegistry.get(name).tag(tag, value).counter().count();
    }
}
