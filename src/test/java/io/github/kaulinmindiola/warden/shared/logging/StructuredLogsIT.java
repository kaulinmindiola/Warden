package io.github.kaulinmindiola.warden.shared.logging;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.notification.application.worker.DeliveryWorker;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Correlation and redaction of the structured logs (REQ-OBS-004, 005, 006, SDD V19). Runs with
 * tracing on, in the same context as {@code RuntimeDefaultsIT}. Requests are made inside an
 * observation, as the HTTP server observation does in production, so they have a trace.
 *
 * <p>The request logs of the dispatcher servlet are switched to DEBUG while a test runs, so that a
 * workspace request writes log lines and the lines are known to leave out headers and bodies.
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=1.0")
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogsIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StructuredLogsIT.class);

    /** The dispatcher servlet of production and the one MockMvc runs. */
    private static final List<String> REQUEST_LOGGERS = List.of(
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.test.web.servlet.TestDispatcherServlet");

    private static final TypeReference<Map<String, Object>> LINE = new TypeReference<>() {};

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @Autowired
    private LoggingSystem loggingSystem;

    @Autowired
    private OutboxDispatchWorker outboxWorker;

    @Autowired
    private DeliveryWorker deliveryWorker;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void restoreLogLevels() {
        REQUEST_LOGGERS.forEach(logger -> loggingSystem.setLogLevel(logger, null));
    }

    @Test
    @Verifies("REQ-OBS-004")
    void everyLineOfAnAuthenticatedWorkspaceRequestIsJsonWithTraceUserAndWorkspace(CapturedOutput output)
            throws Exception {
        IamTestClient iam = new IamTestClient(context);
        String email = iam.registerVerifiedAccount();
        String userId = iam.userIdOf(email).toString();
        String access = iam.login(email);
        String workspace = createWorkspace(iam, access);
        debugRequests();
        String thread = Thread.currentThread().getName();
        int from = output.getOut().length();

        String traceId = traced(() -> {
            iam.get(API + "/workspaces/" + workspace, access).andExpect(status().isOk());
            return tracer.currentSpan().context().traceId();
        });

        List<Map<String, Object>> lines = output.getOut()
                .substring(from)
                .lines()
                .map(this::parse)
                .filter(line -> thread.equals(line.get("thread_name")))
                .toList();
        assertThat(lines).as("lines written while the request was handled").isNotEmpty();
        assertThat(lines)
                .allSatisfy(line -> assertThat(line)
                        .containsKeys("@timestamp", "level", "message")
                        .containsEntry("traceId", traceId)
                        .containsEntry(LogFields.USER_ID, userId)
                        .containsEntry(LogFields.WORKSPACE_ID, workspace));
        assertThat(output.getOut().substring(from)).doesNotContain(access).doesNotContain("Bearer");
    }

    @Test
    @Verifies("REQ-OBS-005")
    void tokensAndEmailAddressesAreMaskedInTheMessageTheMdcAndTheStackTrace(CapturedOutput output) {
        String refreshToken = "wrt_" + UUID.randomUUID().toString().replace("-", "");
        String oneTimeToken = "wot_" + UUID.randomUUID().toString().replace("-", "");
        String jwt = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJ"
                + UUID.randomUUID().toString().replace("-", "") + ".c2lnbmF0dXJl";
        String email = "leak-" + UUID.randomUUID() + "@mail.test";
        String marker = "redaction probe " + UUID.randomUUID();

        MDC.put("probe", jwt + " " + email);
        try {
            log.warn(
                    "{} {} {} {} {}",
                    marker,
                    refreshToken,
                    oneTimeToken,
                    jwt,
                    email,
                    new IllegalStateException("quoted " + refreshToken + " for " + email));
        } finally {
            MDC.remove("probe");
        }

        String line = output.getOut()
                .lines()
                .filter(candidate -> candidate.contains(marker))
                .findFirst()
                .orElseThrow();
        assertThat(line)
                .doesNotContain(refreshToken)
                .doesNotContain(oneTimeToken)
                .doesNotContain(jwt)
                .doesNotContain(email)
                .contains("wrt_" + LogRedactor.TOKEN_MASK)
                .contains("wot_" + LogRedactor.TOKEN_MASK)
                .contains(LogRedactor.JWT_MASK)
                .contains(LogRedactor.EMAIL_MASK);
        Map<String, Object> fields = parse(line);
        assertThat((String) fields.get("probe")).isEqualTo(LogRedactor.JWT_MASK + " " + LogRedactor.EMAIL_MASK);
        assertThat((String) fields.get("stack_trace")).contains("quoted wrt_" + LogRedactor.TOKEN_MASK);
    }

    @Test
    @Verifies("REQ-OBS-005")
    void noPasswordTokenEmailOrAuthorizationHeaderReachesTheLogsOfTheAuthEndpoints(CapturedOutput output)
            throws Exception {
        debugRequests();
        int from = output.getOut().length();
        IamTestClient iam = new IamTestClient(context);
        String email = iam.registerVerifiedAccount();
        IamTestClient.Tokens tokens = iam.loginTokens(email);
        String refreshed = iam.postJson(API + "/auth/refresh", Map.of("refresh_token", tokens.refreshToken()), null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String access = JsonPath.read(refreshed, "$.access_token");
        iam.postJson(API + "/auth/logout", Map.of(), access).andExpect(status().isNoContent());

        String logged = output.getOut().substring(from);
        assertThat(logged).as("the dispatcher logged the requests").contains("/auth/login");
        assertThat(logged)
                .doesNotContain(PASSWORD)
                .doesNotContain(email)
                .doesNotContain(tokens.accessToken())
                .doesNotContain(tokens.refreshToken())
                .doesNotContain(access)
                .doesNotContain((String) JsonPath.read(refreshed, "$.refresh_token"))
                .doesNotContain("Bearer ");
    }

    @Test
    @Verifies("REQ-OBS-006")
    void theTraceOfTheRequestThatInvitesLocatesThePublicationAndTheDeliveryAttempt(CapturedOutput output)
            throws Exception {
        IamTestClient iam = new IamTestClient(context);
        String owner = iam.login(iam.registerVerifiedAccount());
        String workspace = createWorkspace(iam, owner);
        String invitee = "invitee-" + UUID.randomUUID() + "@mail.test";

        String traceId = traced(() -> {
            iam.postJson(
                            API + "/workspaces/" + workspace + "/invitations",
                            Map.of("email", invitee, "role", "MEMBER"),
                            owner)
                    .andExpect(status().isCreated());
            return tracer.currentSpan().context().traceId();
        });
        while (outboxWorker.runOnce() > 0) {
            // publish every pending event
        }
        while (deliveryWorker.runOnce() > 0) {
            // send every due delivery
        }

        List<String> correlated = output.getOut()
                .lines()
                .filter(line -> line.contains("\"" + LogFields.ORIGIN_TRACE_ID + "\":\"" + traceId + "\""))
                .toList();
        assertThat(correlated)
                .anySatisfy(line -> assertThat(line).contains("published"))
                .anySatisfy(line -> assertThat(line).contains("Delivery ").contains("SENT"))
                .noneSatisfy(line -> assertThat(line).contains(invitee));
    }

    private void debugRequests() {
        REQUEST_LOGGERS.forEach(logger -> loggingSystem.setLogLevel(logger, LogLevel.DEBUG));
    }

    private String createWorkspace(IamTestClient iam, String access) throws Exception {
        return JsonPath.read(
                iam.postJson(API + "/workspaces", Map.of("name", "Logged"), access)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private <T> T traced(Observation.CheckedCallable<T, Exception> work) throws Exception {
        return Observation.createNotStarted("test.request", observationRegistry).observeChecked(work);
    }

    private Map<String, Object> parse(String line) {
        assertThat(line).as("a JSON object per line").startsWith("{").endsWith("}");
        return json.readValue(line, LINE);
    }
}
