package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.sessionIdOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IamTestClient.Tokens;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/** REQ-FUNC-012 and REQ-FUNC-014 through the full filter chain, against PostgreSQL and Redis. */
class RefreshReuseAndExpiryIT extends IntegrationTest {

    private static final String REFRESH = API + "/auth/refresh";
    private static final String SESSIONS = API + "/auth/sessions";
    private static final String INVALID_REFRESH = "urn:warden:problem:invalid-refresh-token";

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
    @Verifies({"REQ-FUNC-012", "BR-005"})
    void reusingARefreshTokenRevokesTheWholeSessionWithExactlyOneAlert() throws Exception {
        String email = iam.registerNewAccount();
        Tokens original = iam.loginTokens(email);
        UUID sessionId = sessionIdOf(original.accessToken());
        Tokens rotated = refreshOk(original.refreshToken());

        refresh(original.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(INVALID_REFRESH));

        refresh(rotated.refreshToken()).andExpect(status().isUnauthorized());
        iam.get(SESSIONS, rotated.accessToken()).andExpect(status().isUnauthorized());
        iam.get(SESSIONS, original.accessToken()).andExpect(status().isUnauthorized());
        assertThat(revokeReason(sessionId)).isEqualTo("REUSE");
        assertThat(reuseEvents(sessionId)).isEqualTo(1);
        assertThat(auditCount("REFRESH_TOKEN_REUSE_DETECTED", iam.userIdOf(email)))
                .isEqualTo(1);
        String locale =
                jdbc().sql("""
                        SELECT payload -> 'recipient' ->> 'fallbackLocale' FROM outbox_event
                        WHERE type = 'iam.RefreshTokenReuseDetected' AND payload ->> 'sessionId' = ?
                        """).param(sessionId.toString()).query(String.class).single();
        assertThat(locale).isEqualTo("es");

        refresh(original.refreshToken()).andExpect(status().isUnauthorized());
        assertThat(reuseEvents(sessionId)).isEqualTo(1);
    }

    @Test
    @Verifies("REQ-FUNC-014")
    void aRefreshTokenUnusedFor7DaysExpiresWithoutAlert() throws Exception {
        Tokens login = iam.loginTokens(iam.registerNewAccount());
        UUID sessionId = sessionIdOf(login.accessToken());

        clock.advance(Duration.ofDays(7).plusSeconds(1));

        refresh(login.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(INVALID_REFRESH));
        assertThat(reuseEvents(sessionId)).isZero();
        assertThat(revokeReason(sessionId)).isNull();
    }

    @Test
    @Verifies("REQ-FUNC-014")
    void aSessionRefreshedDailyEndsAt30DaysWithoutAlert() throws Exception {
        Tokens tokens = iam.loginTokens(iam.registerNewAccount());
        UUID sessionId = sessionIdOf(tokens.accessToken());

        for (int day = 1; day <= 29; day++) {
            clock.advance(Duration.ofDays(1));
            tokens = refreshOk(tokens.refreshToken());
        }
        clock.advance(Duration.ofDays(1).plusSeconds(1));

        refresh(tokens.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(INVALID_REFRESH));
        assertThat(reuseEvents(sessionId)).isZero();
        assertThat(revokeReason(sessionId)).isNull();
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return iam.postJson(REFRESH, Map.of("refresh_token", refreshToken), null);
    }

    private Tokens refreshOk(String refreshToken) throws Exception {
        String body = refresh(refreshToken)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Tokens(JsonPath.read(body, "$.access_token"), JsonPath.read(body, "$.refresh_token"));
    }

    private long reuseEvents(UUID sessionId) {
        return jdbc().sql("""
                        SELECT count(*) FROM outbox_event
                        WHERE type = 'iam.RefreshTokenReuseDetected' AND payload ->> 'sessionId' = ?
                        """).param(sessionId.toString()).query(Long.class).single();
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
