package io.github.kaulinmindiola.warden.poc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * POC-001: springdoc, the Prometheus endpoint on the management port and trace ids in the log.
 *
 * <p>Runs without PostgreSQL or Redis, with lazy connections. springdoc is checked through
 * MockMvc because the API document is not public; Prometheus is public on the management port.
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
            "management.tracing.sampling.probability=1.0"
        })
@AutoConfigureMetrics
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class WebSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(WebSmokeIT.class);

    private static final String TRACE_MARKER = "POC-001 trace marker";

    @Autowired
    private Environment environment;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @Test
    void springdocServesTheOpenApiDocument() throws Exception {
        MockMvcBuilders.webAppContextSetup(context)
                .build()
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("\"openapi\"")));
    }

    @Test
    void prometheusIsServedOnTheManagementPortOnly() throws Exception {
        HttpResponse<String> onManagementPort = httpGet(managementPort(), "/actuator/prometheus");
        HttpResponse<String> onServerPort = httpGet(serverPort(), "/actuator/prometheus");

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

    private static HttpResponse<String> httpGet(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
