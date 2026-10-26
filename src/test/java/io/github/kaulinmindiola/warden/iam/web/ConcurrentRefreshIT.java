package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.sessionIdOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IamTestClient.Tokens;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * REQ-FUNC-013 under real concurrency: four HTTP requests with the same refresh token, released
 * together, repeated 50 times. In every repetition exactly one wins and the session ends revoked
 * with a single reuse alert.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ConcurrentRefreshIT extends IntegrationTest {

    private static final int REPETITIONS = 50;
    private static final int CONCURRENT_REQUESTS = 4;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @Verifies("REQ-FUNC-013")
    void exactlyOneOfFourConcurrentRefreshesWinsAndTheSessionEndsRevoked() throws Exception {
        IamTestClient iam = new IamTestClient(context);
        String email = iam.registerNewAccount();
        URI refreshUri = URI.create("http://localhost:" + serverPort() + API + "/auth/refresh");

        try (HttpClient http = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
                ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                Tokens tokens = iam.loginTokens(email);
                UUID sessionId = sessionIdOf(tokens.accessToken());
                HttpRequest request = HttpRequest.newBuilder(refreshUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                jsonMapper.writeValueAsString(Map.of("refresh_token", tokens.refreshToken()))))
                        .build();

                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> responses = new ArrayList<>();
                for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                    responses.add(callers.submit(() -> {
                        start.await();
                        return http.send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode();
                    }));
                }
                start.countDown();

                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> response : responses) {
                    statuses.add(response.get(30, TimeUnit.SECONDS));
                }
                assertThat(statuses).as("statuses in repetition %d", repetition).containsOnly(200, 401);
                assertThat(Collections.frequency(statuses, 200))
                        .as("winners in repetition %d: %s", repetition, statuses)
                        .isEqualTo(1);
                assertThat(revokeReason(sessionId))
                        .as("session state in repetition %d", repetition)
                        .isEqualTo("REUSE");
                assertThat(reuseEvents(sessionId))
                        .as("reuse alerts in repetition %d", repetition)
                        .isEqualTo(1);
            }
        }
        System.out.println("REQ-FUNC-013: " + REPETITIONS + " repetitions of " + CONCURRENT_REQUESTS
                + " concurrent refreshes, exactly one winner and one alert each");
    }

    private int serverPort() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }

    private String revokeReason(UUID sessionId) {
        return (String) jdbc().sql("SELECT revoke_reason FROM auth_session WHERE id = ?")
                .param(sessionId)
                .query()
                .singleRow()
                .get("revoke_reason");
    }

    private long reuseEvents(UUID sessionId) {
        return jdbc().sql("""
                        SELECT count(*) FROM outbox_event
                        WHERE type = 'iam.RefreshTokenReuseDetected' AND payload ->> 'sessionId' = ?
                        """).param(sessionId.toString()).query(Long.class).single();
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
