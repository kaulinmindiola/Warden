package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.iam.domain.RevocationStore;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.context.WebApplicationContext;

/**
 * Redis part of REQ-REL-007: with a revocation store that cannot answer or write, PostgreSQL
 * still rejects revoked sessions and accepts active ones.
 */
class RevocationFallbackIT extends IntegrationTest {

    private static final String SESSIONS = API + "/auth/sessions";
    private static final String LOGOUT = API + "/auth/logout";

    @Autowired
    private WebApplicationContext context;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    void whenTheRevocationStoreFailsPostgresStillRejectsRevokedSessions() throws Exception {
        String email = iam.registerNewAccount();
        String loggedOut = iam.login(email);
        String active = iam.login(email);

        iam.postJson(LOGOUT, Map.of(), loggedOut).andExpect(status().isNoContent());

        iam.get(SESSIONS, loggedOut).andExpect(status().isUnauthorized());
        iam.get(SESSIONS, active).andExpect(status().isOk());
    }

    /** Replaces the Redis store with one that always fails, only in this test's context. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingRevocationStoreConfiguration {

        @Bean
        @Primary
        RevocationStore failingRevocationStore() {
            return new RevocationStore() {
                @Override
                public void markRevoked(UUID sessionId) {
                    // Simulates a write lost while Redis is down.
                }

                @Override
                public State state(UUID sessionId) {
                    return State.UNKNOWN;
                }
            };
        }
    }
}
