package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/**
 * SDD V14-A3: both branches of registration and of the password reset request take at least the
 * configured floor. A value different from the default proves that it is configurable; the
 * statistical analysis of REQ-SEC-010 is out of scope (ADR-019).
 */
@SpringBootTest(properties = "warden.security.response-floor=300ms")
class ResponseFloorIT extends IntegrationTest {

    private static final Duration FLOOR = Duration.ofMillis(300);

    @Autowired
    private WebApplicationContext context;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies("BR-007")
    void registrationTakesAtLeastTheFloorForNewAndExistingEmails() throws Exception {
        String email = "floor-" + UUID.randomUUID() + "@mail.com";
        Map<String, String> body = Map.of("email", email, "password", PASSWORD);

        Duration newAccount =
                timed(() -> iam.postJson(API + "/auth/register", body, null).andExpect(status().isAccepted()));
        Duration existingAccount =
                timed(() -> iam.postJson(API + "/auth/register", body, null).andExpect(status().isAccepted()));

        assertThat(newAccount).isGreaterThanOrEqualTo(FLOOR);
        assertThat(existingAccount).isGreaterThanOrEqualTo(FLOOR);
    }

    @Test
    @Verifies("BR-007")
    void aResetRequestTakesAtLeastTheFloorForExistingAndUnknownEmails() throws Exception {
        String email = iam.registerNewAccount();
        String unknown = "nobody-" + UUID.randomUUID() + "@mail.com";

        Duration existing = timed(() -> iam.postJson(API + "/auth/password/forgot", Map.of("email", email), null)
                .andExpect(status().isAccepted()));
        Duration missing = timed(() -> iam.postJson(API + "/auth/password/forgot", Map.of("email", unknown), null)
                .andExpect(status().isAccepted()));

        assertThat(existing).isGreaterThanOrEqualTo(FLOOR);
        assertThat(missing).isGreaterThanOrEqualTo(FLOOR);
    }

    private static Duration timed(Request request) throws Exception {
        long start = System.nanoTime();
        request.send();
        return Duration.ofNanos(System.nanoTime() - start);
    }

    @FunctionalInterface
    private interface Request {
        ResultActions send() throws Exception;
    }
}
