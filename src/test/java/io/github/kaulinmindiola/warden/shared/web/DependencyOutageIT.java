package io.github.kaulinmindiola.warden.shared.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import io.github.kaulinmindiola.warden.iam.infrastructure.RevocationReconciler;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL, alone or together with Redis, really unreachable (REQ-REL-007, REQ-REL-009,
 * REQ-AVAIL-001; SDD V17; ADR-007). The shared containers are frozen with {@code docker pause};
 * requests go through a real server, so the servlet filters and the security chain apply. After
 * every test both containers answer again, the pool has fresh connections and the revocation store
 * is back on Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class DependencyOutageIT extends IntegrationTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private Environment environment;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RevocationReconciler reconciler;

    private IamTestClient iam;
    private boolean postgresPaused;
    private boolean redisPaused;

    @BeforeEach
    void setUp() {
        iam = new IamTestClient(context);
    }

    @AfterEach
    void restoreDependencies() throws InterruptedException {
        if (postgresPaused) {
            resumePostgres();
            postgresPaused = false;
        }
        if (redisPaused) {
            resumeRedis();
            redisPaused = false;
        }
        awaitRecovery();
    }

    @Test
    @Verifies("REQ-REL-009")
    void withPostgresUnavailableAuthenticationAndWorkspaceRequestsAnswer503() throws Exception {
        String email = iam.registerVerifiedAccount();
        IamTestClient.Tokens tokens = iam.loginTokens(email);
        UUID workspaceId = createWorkspace(tokens.accessToken());

        freezePostgres();

        List<HttpResponse<String>> responses = List.of(
                post(
                        "/auth/register",
                        Map.of("email", "outage-" + UUID.randomUUID() + "@mail.com", "password", PASSWORD),
                        null),
                post("/auth/login", Map.of("email", email, "password", PASSWORD), null),
                post("/auth/refresh", Map.of("refresh_token", tokens.refreshToken()), null),
                get("/auth/sessions", tokens.accessToken()),
                get("/workspaces/" + workspaceId, tokens.accessToken()));

        assertThat(responses).allSatisfy(DependencyOutageIT::assertServiceUnavailable);
    }

    @Test
    @Verifies("REQ-REL-007")
    void withRedisAndPostgresUnavailableAnAuthenticatedRequestAnswers503() throws Exception {
        String accessToken = iam.login(iam.registerNewAccount());

        pauseRedis();
        redisPaused = true;
        freezePostgres();

        assertServiceUnavailable(get("/auth/sessions", accessToken));
        assertServiceUnavailable(post("/auth/logout", Map.of(), accessToken));
    }

    @Test
    @Verifies("REQ-AVAIL-001")
    void readinessIsDownOnlyWhilePostgresIsUnavailable() throws Exception {
        assertThat(management("/actuator/health/readiness").statusCode()).isEqualTo(200);

        pauseRedis();
        redisPaused = true;
        assertThat(management("/actuator/health/readiness").statusCode())
                .as("readiness with Redis down")
                .isEqualTo(200);
        resumeRedis();
        redisPaused = false;

        freezePostgres();
        HttpResponse<String> readiness = management("/actuator/health/readiness");
        assertThat(readiness.statusCode()).as("readiness with PostgreSQL down").isEqualTo(503);
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
        assertThat(management("/actuator/health/liveness").statusCode())
                .as("liveness with PostgreSQL down")
                .isEqualTo(200);

        resumePostgres();
        postgresPaused = false;
        awaitReadiness();
    }

    /**
     * Freezes PostgreSQL and evicts the idle pooled connections, so every request needs a new
     * connection and fails at the 2 s Hikari timeout instead of waiting on a frozen socket.
     */
    private void freezePostgres() throws SQLException {
        pausePostgres();
        postgresPaused = true;
        dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
    }

    private static void assertServiceUnavailable(HttpResponse<String> response) {
        String path = response.request().uri().getPath();
        assertThat(response.statusCode()).as("status of %s", path).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After"))
                .as("Retry-After of %s", path)
                .contains("5");
        assertThat(response.headers().firstValue("Content-Type"))
                .as("Content-Type of %s", path)
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body())
                .as("body of %s", path)
                .contains("\"type\":\"" + ProblemType.SERVICE_UNAVAILABLE.uri() + "\"");
    }

    private UUID createWorkspace(String accessToken) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", "Outage " + UUID.randomUUID()), accessToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void awaitRecovery() throws InterruptedException {
        long deadline = System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
        while (!(databaseAnswers() && reconciler.recover())) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("PostgreSQL and Redis did not recover within " + RECOVERY_TIMEOUT);
            }
            Thread.sleep(250);
        }
    }

    private void awaitReadiness() throws Exception {
        long deadline = System.nanoTime() + RECOVERY_TIMEOUT.toNanos();
        while (management("/actuator/health/readiness").statusCode() != 200) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("Readiness did not return to UP within " + RECOVERY_TIMEOUT);
            }
            Thread.sleep(250);
        }
    }

    private boolean databaseAnswers() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (SQLException unavailable) {
            return false;
        }
    }

    private HttpResponse<String> post(String path, Map<String, ?> body, String accessToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(apiUri(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return send(request.build());
    }

    private HttpResponse<String> get(String path, String accessToken) throws Exception {
        return send(HttpRequest.newBuilder(apiUri(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build());
    }

    private HttpResponse<String> management(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(port("local.management.port"), path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build());
    }

    private URI apiUri(String path) {
        return uri(port("local.server.port"), API + path);
    }

    private int port(String property) {
        return environment.getRequiredProperty(property, Integer.class);
    }

    private static URI uri(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
