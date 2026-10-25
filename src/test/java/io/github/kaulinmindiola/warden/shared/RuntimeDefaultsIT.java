package io.github.kaulinmindiola.warden.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.AsyncTaskExecutor;

/** Runtime defaults: identifiers from the application clock, virtual threads and JSON logs. */
@SpringBootTest(properties = "management.tracing.sampling.probability=1.0")
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class RuntimeDefaultsIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RuntimeDefaultsIT.class);

    private static final String MARKER = "structured logging marker";

    @Autowired
    private UuidV7Generator ids;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private AsyncTaskExecutor applicationTaskExecutor;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @Test
    @Verifies("REQ-INT-007")
    void identifiersUseTheApplicationClock() {
        Instant now = Instant.parse("2026-03-04T05:06:07.890Z");
        clock.setInstant(now);

        UUID id = ids.next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
    }

    @Test
    void applicationTasksRunOnVirtualThreads() throws Exception {
        Boolean virtual = applicationTaskExecutor
                .submitCompletable(() -> Thread.currentThread().isVirtual())
                .get();

        assertThat(virtual).isTrue();
    }

    @Test
    @Verifies("REQ-OBS-004")
    void logLinesAreJsonObjectsWithTheTraceId(CapturedOutput output) {
        String[] traceId = new String[1];

        Observation.createNotStarted("runtime.logging", observationRegistry).observe(() -> {
            traceId[0] = tracer.currentSpan().context().traceId();
            log.info(MARKER);
        });

        String line = output.getOut()
                .lines()
                .filter(candidate -> candidate.contains(MARKER))
                .findFirst()
                .orElseThrow();
        System.out.println("Structured log line sample: " + line);
        assertThat(line).startsWith("{").endsWith("}");
        assertThat(line).contains("\"message\":\"" + MARKER + "\"");
        assertThat(line).contains("\"traceId\":\"" + traceId[0] + "\"");
    }
}
