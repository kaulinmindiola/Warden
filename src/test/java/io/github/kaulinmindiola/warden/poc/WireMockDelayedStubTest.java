package io.github.kaulinmindiola.warden.poc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** POC-001: an in-JVM WireMock stub with a fixed delay drives the JDK HttpClient timeouts. */
class WireMockDelayedStubTest {

    private static final int DELAY_MILLIS = 300;

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        server.stubFor(
                get(urlEqualTo("/send")).willReturn(aResponse().withStatus(202).withFixedDelay(DELAY_MILLIS)));
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void aShorterTimeoutFailsAgainstTheSlowStub() {
        HttpRequest request = request(Duration.ofMillis(100));

        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(HttpTimeoutException.class);
        }
    }

    @Test
    void aLongerTimeoutReceivesTheDelayedResponse() throws Exception {
        HttpRequest request = request(Duration.ofSeconds(2));

        try (HttpClient client = HttpClient.newHttpClient()) {
            long start = System.nanoTime();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(DELAY_MILLIS);
        }
    }

    private HttpRequest request(Duration timeout) {
        return HttpRequest.newBuilder(URI.create(server.baseUrl() + "/send"))
                .timeout(timeout)
                .GET()
                .build();
    }
}
