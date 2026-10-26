package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.sessionIdOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.WebApplicationContext;

/** REQ-FUNC-018: the first request after a revocation is rejected, with zero tolerance. */
class SessionRevocationIT extends IntegrationTest {

    private static final String SESSIONS = API + "/auth/sessions";
    private static final String LOGOUT = API + "/auth/logout";
    private static final String UNAUTHENTICATED = "urn:warden:problem:unauthenticated";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StringRedisTemplate redis;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies({"REQ-FUNC-018", "REQ-FUNC-015"})
    void theFirstRequestAfterLogoutIsRejected() throws Exception {
        String token = iam.login(iam.registerNewAccount());
        iam.get(SESSIONS, token).andExpect(status().isOk());

        iam.postJson(LOGOUT, Map.of(), token).andExpect(status().isNoContent());

        iam.get(SESSIONS, token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(UNAUTHENTICATED));
        Long ttl = redis.getExpire("warden:revoked:" + sessionIdOf(token));
        assertThat(ttl).isBetween(600L, 630L);
    }

    @Test
    @Verifies({"REQ-FUNC-018", "REQ-FUNC-017"})
    void theFirstRequestAfterTerminatingASessionIsRejectedAndOtherSessionsKeepWorking() throws Exception {
        String email = iam.registerNewAccount();
        String kept = iam.login(email);
        String terminated = iam.login(email);

        iam.delete(SESSIONS + "/" + sessionIdOf(terminated), kept).andExpect(status().isNoContent());

        iam.get(SESSIONS, terminated).andExpect(status().isUnauthorized());
        iam.get(SESSIONS, kept).andExpect(status().isOk());
    }

    @Test
    @Verifies("REQ-FUNC-018")
    void aLoggedOutTokenCannotLogOutAgain() throws Exception {
        String token = iam.login(iam.registerNewAccount());

        iam.postJson(LOGOUT, Map.of(), token).andExpect(status().isNoContent());

        iam.postJson(LOGOUT, Map.of(), token).andExpect(status().isUnauthorized());
    }
}
