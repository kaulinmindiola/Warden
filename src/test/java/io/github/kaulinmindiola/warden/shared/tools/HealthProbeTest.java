package io.github.kaulinmindiola.warden.shared.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The health probe that the container runs (REQ-AVAIL-001). */
class HealthProbeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/up", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/down", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @Verifies("REQ-AVAIL-001")
    void aHealthyEndpointPasses() {
        assertThat(HealthProbe.isHealthy(url("/up"), TIMEOUT)).isTrue();
    }

    @Test
    @Verifies("REQ-AVAIL-001")
    void anUnhealthyEndpointFails() {
        assertThat(HealthProbe.isHealthy(url("/down"), TIMEOUT)).isFalse();
    }

    @Test
    void anUnreachableEndpointFails() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThat(HealthProbe.isHealthy(URI.create("http://127.0.0.1:" + closedPort + "/up"), TIMEOUT))
                .isFalse();
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
