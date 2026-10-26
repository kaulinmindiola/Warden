package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.sessionIdOf;
import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IamTestClient.Tokens;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/** UC-04 through the full filter chain, against PostgreSQL. */
class RefreshIT extends IntegrationTest {

    private static final String REFRESH = API + "/auth/refresh";
    private static final String SESSIONS = API + "/auth/sessions";
    private static final String LOGOUT = API + "/auth/logout";
    private static final String INVALID_REFRESH = "urn:warden:problem:invalid-refresh-token";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TokenHasher tokenHasher;

    @Autowired
    private SecureTokenGenerator tokenGenerator;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies({"REQ-FUNC-011", "REQ-INT-003"})
    void aValidRefreshRotatesTheTokenWithinTheSameSession() throws Exception {
        Tokens login = iam.loginTokens(iam.registerNewAccount());
        clock.advance(Duration.ofMinutes(5));
        Instant refreshedAt = clock.instant();

        MvcResult result = refresh(login.refreshToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(600))
                .andExpect(jsonPath("$.refresh_expires_in").value(604_800))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Map<String, Object> fields = JsonPath.read(body, "$");
        String newAccess = JsonPath.read(body, "$.access_token");
        String newRefresh = JsonPath.read(body, "$.refresh_token");
        assertThat(fields.keySet())
                .containsExactlyInAnyOrder(
                        "access_token", "token_type", "expires_in", "refresh_token", "refresh_expires_in");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(newRefresh).startsWith("wrt_").isNotEqualTo(login.refreshToken());
        UUID sessionId = sessionIdOf(login.accessToken());
        assertThat(sessionIdOf(newAccess)).isEqualTo(sessionId);

        assertThat(timestamp(
                        "SELECT used_at FROM refresh_token WHERE token_hash = ?",
                        tokenHasher.sha256(login.refreshToken())))
                .isEqualTo(refreshedAt);
        assertThat(timestamp(
                        "SELECT expires_at FROM refresh_token WHERE token_hash = ?", tokenHasher.sha256(newRefresh)))
                .isEqualTo(refreshedAt.plus(Duration.ofDays(7)));
        assertThat(timestamp("SELECT last_used_at FROM auth_session WHERE id = ?", sessionId))
                .isEqualTo(refreshedAt);
        iam.get(SESSIONS, newAccess).andExpect(status().isOk());
    }

    @Test
    @Verifies("REQ-INT-002")
    void aRefreshTokenInTheAuthorizationHeaderIsRejectedWithoutBeingConsumed() throws Exception {
        Tokens login = iam.loginTokens(iam.registerNewAccount());

        iam.postJson(REFRESH, Map.of("refresh_token", login.refreshToken()), login.refreshToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:malformed-request"));

        assertThat(timestamp(
                        "SELECT used_at FROM refresh_token WHERE token_hash = ?",
                        tokenHasher.sha256(login.refreshToken())))
                .isNull();
        refresh(login.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void aUsedAndAnUnknownRefreshTokenGetTheSame401() throws Exception {
        Tokens login = iam.loginTokens(iam.registerNewAccount());
        refresh(login.refreshToken()).andExpect(status().isOk());

        MvcResult used = refresh(login.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(INVALID_REFRESH))
                .andReturn();
        MvcResult unknown = refresh(tokenGenerator.newRefreshToken()).andReturn();
        MvcResult malformed = refresh("not-a-refresh-token").andReturn();

        assertIdenticalResponses(unknown, used);
        assertIdenticalResponses(unknown, malformed);
    }

    @Test
    void theRefreshTokenOfALoggedOutSessionIsRejectedAndLeftUnused() throws Exception {
        Tokens login = iam.loginTokens(iam.registerNewAccount());
        iam.postJson(LOGOUT, Map.of(), login.accessToken()).andExpect(status().isNoContent());

        refresh(login.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(INVALID_REFRESH));

        assertThat(timestamp(
                        "SELECT used_at FROM refresh_token WHERE token_hash = ?",
                        tokenHasher.sha256(login.refreshToken())))
                .isNull();
        long tokensOfSession = jdbc().sql("SELECT count(*) FROM refresh_token WHERE session_id = ?")
                .param(sessionIdOf(login.accessToken()))
                .query(Long.class)
                .single();
        assertThat(tokensOfSession).isEqualTo(1);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return iam.postJson(REFRESH, Map.of("refresh_token", refreshToken), null);
    }

    /** Reads one nullable timestamp; the row mapper returns an Optional because single() rejects null. */
    private Instant timestamp(String sql, Object parameter) {
        return jdbc().sql(sql)
                .param(parameter)
                .query((rs, rowNum) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class)))
                .single()
                .map(OffsetDateTime::toInstant)
                .orElse(null);
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
