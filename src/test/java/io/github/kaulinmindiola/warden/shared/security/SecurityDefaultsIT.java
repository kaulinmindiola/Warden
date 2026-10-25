package io.github.kaulinmindiola.warden.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * Deny-by-default security through a real server, so the servlet filter chain applies. The
 * management server runs on its own random port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class SecurityDefaultsIT extends IntegrationTest {

    @Autowired
    private Environment environment;

    @Test
    @Verifies("REQ-SEC-001")
    void anyApiPathRequiresAuthentication() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(serverPort(), "/api/v1/anything"))
                .GET()
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.body()).contains("\"type\":\"" + ProblemType.UNAUTHENTICATED.uri() + "\"");
        assertThat(response.body()).contains("\"traceId\"");
    }

    @Test
    @Verifies("REQ-SEC-001")
    void anUnauthenticatedWriteIsRejectedWith401RatherThanACsrfError() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(serverPort(), "/api/v1/anything"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void noSessionCookieIsCreated() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(serverPort(), "/api/v1/anything"))
                .GET()
                .build());

        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void healthIsPublicOnTheManagementPort() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(managementPort(), "/actuator/health"))
                .GET()
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void actuatorIsNotServedOnTheApplicationPort() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(serverPort(), "/actuator/health"))
                .GET()
                .build());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private int serverPort() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }

    private int managementPort() {
        return environment.getRequiredProperty("local.management.port", Integer.class);
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
