package io.github.kaulinmindiola.warden.poc;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;

/**
 * POC-001: springdoc, the Prometheus endpoint on the management port and trace ids in the log.
 *
 * <p>Runs without PostgreSQL or Redis, with the same temporary properties as the context test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:postgresql://localhost:5432/warden",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
            "spring.flyway.enabled=false",
            "management.server.port=0",
            "management.endpoints.web.exposure.include=health,prometheus",
            "management.tracing.sampling.probability=1.0",
            "spring.security.user.name=poc",
            "spring.security.user.password=poc"
        })
@AutoConfigureMetrics
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class WebSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(WebSmokeIT.class);

    private static final String BASIC_AUTH =
            "Basic " + Base64.getEncoder().encodeToString("poc:poc".getBytes(StandardCharsets.UTF_8));

    private static final String TRACE_MARKER = "POC-001 trace marker";

    @Autowired
    private Environment environment;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @Test
    void springdocServesTheOpenApiDocument() throws Exception {
        HttpResponse<String> response = get(serverPort(), "/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"openapi\"");
    }

    @Test
    void prometheusIsServedOnTheManagementPortOnly() throws Exception {
        HttpResponse<String> onManagementPort = get(managementPort(), "/actuator/prometheus");
        HttpResponse<String> onServerPort = get(serverPort(), "/actuator/prometheus");

        assertThat(onManagementPort.statusCode()).isEqualTo(200);
        assertThat(onManagementPort.body()).contains("jvm_memory_used_bytes");
        assertThat(onServerPort.statusCode()).isNotEqualTo(200);
    }

    @Test
    void theTraceIdOfTheCurrentSpanAppearsInTheLogLine(CapturedOutput output) {
        String[] traceId = new String[1];

        Observation.createNotStarted("poc.trace", observationRegistry).observe(() -> {
            traceId[0] = tracer.currentSpan().context().traceId();
            log.info(TRACE_MARKER);
        });

        String markerLine = output.getOut()
                .lines()
                .filter(line -> line.contains(TRACE_MARKER))
                .findFirst()
                .orElseThrow();
        System.out.println("POC-001 trace id in log: " + traceId[0]);
        assertThat(traceId[0]).matches("[0-9a-f]{32}");
        assertThat(markerLine).contains(traceId[0]);
    }

    private int serverPort() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }

    private int managementPort() {
        return environment.getRequiredProperty("local.management.port", Integer.class);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", BASIC_AUTH)
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
