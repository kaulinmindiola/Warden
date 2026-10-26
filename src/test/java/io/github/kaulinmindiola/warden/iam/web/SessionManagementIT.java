package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.sessionIdOf;
import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/** UC-05 to UC-07 through the full filter chain, against PostgreSQL. */
class SessionManagementIT extends IntegrationTest {

    private static final String SESSIONS = API + "/auth/sessions";
    private static final String LOGOUT = API + "/auth/logout";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies("REQ-FUNC-016")
    void eachUserSeesOnlyTheirActiveSessionsWithExactlyOneMarkedCurrent() throws Exception {
        String ana = iam.registerNewAccount();
        String bob = iam.registerNewAccount();
        List<String> anaTokens =
                List.of(iam.login(ana, "UA-ana-1"), iam.login(ana, "UA-ana-2"), iam.login(ana, "UA-ana-3"));
        List<String> bobTokens =
                List.of(iam.login(bob, "UA-bob-1"), iam.login(bob, "UA-bob-2"), iam.login(bob, "UA-bob-3"));

        String anaBody = iam.get(SESSIONS, anaTokens.get(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String bobBody = iam.get(SESSIONS, bobTokens.get(0))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> anaIds = JsonPath.read(anaBody, "$.items[*].id");
        List<String> bobIds = JsonPath.read(bobBody, "$.items[*].id");
        List<String> anaCurrent = JsonPath.read(anaBody, "$.items[?(@.current == true)].id");
        List<String> userAgents = JsonPath.read(anaBody, "$.items[*].userAgent");
        assertThat(anaIds).containsExactlyInAnyOrderElementsOf(sessionIds(anaTokens));
        assertThat(bobIds).containsExactlyInAnyOrderElementsOf(sessionIds(bobTokens));
        assertThat(anaIds).doesNotContainAnyElementsOf(bobIds);
        assertThat(anaCurrent).containsExactly(sessionIdOf(anaTokens.get(1)).toString());
        assertThat(userAgents).containsExactlyInAnyOrder("UA-ana-1", "UA-ana-2", "UA-ana-3");

        iam.get(SESSIONS + "?size=2", anaTokens.get(0))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @Verifies("REQ-FUNC-016")
    void revokedAndExpiredSessionsAreNotListed() throws Exception {
        String email = iam.registerNewAccount();
        String first = iam.login(email);
        String second = iam.login(email);

        iam.delete(SESSIONS + "/" + sessionIdOf(first), second).andExpect(status().isNoContent());
        iam.get(SESSIONS, second)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(sessionIdOf(second).toString()));

        clock.advance(Duration.ofDays(30).plusSeconds(1));
        String third = iam.login(email);

        iam.get(SESSIONS, third)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(sessionIdOf(third).toString()))
                .andExpect(jsonPath("$.items[0].current").value(true));
    }

    @Test
    @Verifies("REQ-FUNC-017")
    void anotherUsersSessionGetsTheSame404AsAnUnknownOneAndStaysActive() throws Exception {
        String anaToken = iam.login(iam.registerNewAccount());
        String bobToken = iam.login(iam.registerNewAccount());
        UUID anaSession = sessionIdOf(anaToken);

        MvcResult foreign = iam.delete(SESSIONS + "/" + anaSession, bobToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:not-found"))
                .andReturn();
        MvcResult unknown =
                iam.delete(SESSIONS + "/" + UUID.randomUUID(), bobToken).andReturn();

        assertIdenticalResponses(unknown, foreign);
        assertThat(revokedAt(anaSession)).isNull();
        iam.get(SESSIONS, anaToken).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @Verifies({"REQ-FUNC-017", "REQ-SEC-015"})
    void terminatingAnOwnSessionRevokesItOnceAndAuditsIt() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        String kept = iam.login(email);
        String other = iam.login(email);
        UUID otherSession = sessionIdOf(other);

        iam.delete(SESSIONS + "/" + otherSession, kept).andExpect(status().isNoContent());
        iam.delete(SESSIONS + "/" + otherSession, kept).andExpect(status().isNoContent());

        assertThat(revokedAt(otherSession)).isNotNull();
        assertThat(revokeReason(otherSession)).isEqualTo("TERMINATED");
        assertThat(auditCount("SESSION_TERMINATED", userId)).isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-FUNC-015", "REQ-SEC-015"})
    void logoutRevokesTheCurrentSessionOnly() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        String loggingOut = iam.login(email);
        String staying = iam.login(email);

        iam.postJson(LOGOUT, Map.of(), loggingOut).andExpect(status().isNoContent());

        assertThat(revokeReason(sessionIdOf(loggingOut))).isEqualTo("LOGOUT");
        assertThat(revokedAt(sessionIdOf(staying))).isNull();
        assertThat(auditCount("LOGOUT", userId)).isEqualTo(1);
        iam.get(SESSIONS, staying)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(sessionIdOf(staying).toString()));
    }

    private static List<String> sessionIds(List<String> tokens) {
        return tokens.stream().map(token -> sessionIdOf(token).toString()).toList();
    }

    private Object revokedAt(UUID sessionId) {
        return jdbc().sql("SELECT revoked_at FROM auth_session WHERE id = ?")
                .param(sessionId)
                .query()
                .singleRow()
                .get("revoked_at");
    }

    private String revokeReason(UUID sessionId) {
        return (String) jdbc().sql("SELECT revoke_reason FROM auth_session WHERE id = ?")
                .param(sessionId)
                .query()
                .singleRow()
                .get("revoke_reason");
    }

    private long auditCount(String action, UUID userId) {
        return jdbc().sql("SELECT count(*) FROM audit_log WHERE action = ? AND actor_user_id = ?")
                .param(action)
                .param(userId)
                .query(Long.class)
                .single();
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
