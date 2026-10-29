package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rate limits of {@code iam} through the API, with the SRS values (DEC-005, DEC-031).
 *
 * <p>The shared test context raises the per-IP capacities because every test request comes from
 * 127.0.0.1; this context restores the SRS ones. Each test sends its requests from its own random
 * client address, so tests never spend each other's limits.
 */
@SpringBootTest(
        properties = {
            "warden.ratelimit.login-per-ip.capacity=20",
            "warden.ratelimit.password-reset-per-ip.capacity=10",
            "warden.ratelimit.refresh-per-ip.capacity=60"
        })
class IamRateLimitIT extends IntegrationTest {

    private static final String LOGIN = API + "/auth/login";
    private static final String REFRESH = API + "/auth/refresh";
    private static final String FORGOT = API + "/auth/password/forgot";
    private static final String RESEND = API + "/auth/email/verify/resend";
    private static final String WRONG_PASSWORD = "not the right password";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WebApplicationContext context;

    private IamTestClient iam;
    private String clientIp;

    @BeforeEach
    void setUp() {
        iam = new IamTestClient(context);
        clientIp = randomIp();
    }

    @Test
    @Verifies("REQ-SEC-006")
    void afterFiveFailuresTheEmailIsRejectedEvenWithTheRightPassword() throws Exception {
        String email = iam.registerNewAccount();
        failLogins(email, 5);

        expectRateLimited(login(email, PASSWORD), Duration.ofMinutes(15));
    }

    @Test
    @Verifies("REQ-SEC-006")
    void theEmailIsAdmittedAgainOnceTheWindowHasPassed() throws Exception {
        String email = iam.registerNewAccount();
        failLogins(email, 5);

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        login(email, PASSWORD).andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofSeconds(2));
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @Verifies({"REQ-SEC-006", "BR-007"})
    void anUnknownEmailIsLimitedExactlyLikeAnExistingOne() throws Exception {
        String existing = iam.registerNewAccount();
        String unknown = "nobody-" + UUID.randomUUID() + "@mail.com";
        failLogins(existing, 5);
        failLogins(unknown, 5);

        MvcResult existingRejected = login(existing, PASSWORD).andReturn();
        MvcResult unknownRejected = login(unknown, PASSWORD).andReturn();

        assertThat(existingRejected.getResponse().getStatus()).isEqualTo(429);
        assertIdenticalResponses(existingRejected, unknownRejected);
        assertThat(unknownRejected.getResponse().getHeader("Retry-After"))
                .isEqualTo(existingRejected.getResponse().getHeader("Retry-After"));
    }

    @Test
    @Verifies("REQ-SEC-006")
    void successfulLoginsDoNotCountAsFailures() throws Exception {
        String email = iam.registerNewAccount();
        for (int attempt = 1; attempt <= 6; attempt++) {
            login(email, PASSWORD).andExpect(status().isOk());
        }

        failLogins(email, 5);

        login(email, PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    @Verifies("REQ-SEC-007")
    void theTwentyFirstLoginFromOneIpWithinAMinuteIsRejected() throws Exception {
        for (int attempt = 1; attempt <= 20; attempt++) {
            login("ip-" + UUID.randomUUID() + "@mail.com", WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }

        expectRateLimited(login("ip-" + UUID.randomUUID() + "@mail.com", PASSWORD), Duration.ofMinutes(1));
    }

    @Test
    @Verifies("REQ-SEC-008")
    void theFourthResetRequestForAnEmailWithinAnHourIsRejected() throws Exception {
        String email = iam.registerNewAccount();
        for (int request = 1; request <= 3; request++) {
            forgot(email).andExpect(status().isAccepted());
        }

        expectRateLimited(forgot(email), Duration.ofHours(1));

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        forgot(email).andExpect(status().isAccepted());
    }

    @Test
    @Verifies("REQ-SEC-008")
    void theEleventhResetRequestFromOneIpWithinAnHourIsRejected() throws Exception {
        for (int request = 1; request <= 10; request++) {
            forgot("reset-" + UUID.randomUUID() + "@mail.com").andExpect(status().isAccepted());
        }

        expectRateLimited(forgot("reset-" + UUID.randomUUID() + "@mail.com"), Duration.ofHours(1));
    }

    @Test
    @Verifies("REQ-SEC-009")
    void theSixtyFirstRefreshFromOneIpWithinAMinuteIsRejected() throws Exception {
        for (int request = 1; request <= 60; request++) {
            refresh("wrt_" + UUID.randomUUID()).andExpect(status().isUnauthorized());
        }

        expectRateLimited(refresh("wrt_" + UUID.randomUUID()), Duration.ofMinutes(1));
    }

    @Test
    @Verifies("REQ-FUNC-007")
    void theFourthVerificationResendWithinAnHourIsRejected() throws Exception {
        String email = iam.registerNewAccount();
        String accessToken = iam.login(email);
        for (int request = 1; request <= 3; request++) {
            iam.postJson(RESEND, Map.of(), accessToken).andExpect(status().isAccepted());
        }

        expectRateLimited(iam.postJson(RESEND, Map.of(), accessToken), Duration.ofHours(1));
    }

    private void failLogins(String email, int failures) throws Exception {
        for (int attempt = 1; attempt <= failures; attempt++) {
            login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }
    }

    private static void expectRateLimited(ResultActions result, Duration window) throws Exception {
        MvcResult rejected = result.andExpect(status().isTooManyRequests())
                .andExpect(
                        jsonPath("$.type").value(ProblemType.RATE_LIMITED.uri().toString()))
                .andReturn();
        String retryAfter = rejected.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).as("Retry-After").isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, window.toSeconds());
    }

    private ResultActions login(String email, String password) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        return post(LOGIN, body);
    }

    private ResultActions forgot(String email) throws Exception {
        return post(FORGOT, Map.of("email", email));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return post(REFRESH, Map.of("refresh_token", refreshToken));
    }

    private ResultActions post(String path, Map<String, ?> body) throws Exception {
        String ip = clientIp;
        return iam.mvc()
                .perform(MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body))
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        }));
    }

    /** A client address in 10.0.0.0/8, unique enough that no two tests share a per-IP bucket. */
    private static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }
}
