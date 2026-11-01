package io.github.kaulinmindiola.warden;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.notification.application.worker.DeliveryWorker;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Fitness function FF-09: the telemetry never carries a secret, an e-mail address or a
 * high-cardinality identifier (REQ-OBS-003, REQ-OBS-005, SDD V19).
 *
 * <p>Runs with metrics exported in Prometheus format and tracing on. Requests pass through the HTTP
 * server observation filter, as in production, so the HTTP metrics exist. A flow issues every kind
 * of secret: registration and verification, login and a failed login, refresh and the reuse of a
 * rotated token, a workspace with a note, an accepted and a pending invitation, a password reset,
 * logout, and the deliveries of all of it, rendered with their links and sent. Then:
 *
 * <ul>
 *   <li>the log output of the flow is searched for every password, token, token hash and e-mail it
 *       generated: no match;
 *   <li>every label value of the Prometheus scrape is searched for the same secrets and for every
 *       user, workspace, note, invitation and session id and the client address: no match; and no
 *       label value has the shape of a UUID, an e-mail address or an IPv4 address.
 * </ul>
 *
 * The scrape holds the series of everything this context has served, not only the flow's. The one
 * address allowed is the Redis endpoint in the {@code local} and {@code remote} labels of the
 * Lettuce client metrics: a fixed infrastructure address, neither a client's nor a source of new
 * series.
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=1.0")
@AutoConfigureTracing
@AutoConfigureMetrics
@ExtendWith(OutputCaptureExtension.class)
class TelemetryScanIT extends IntegrationTest {

    private static final String NEW_PASSWORD = "a telemetry scan passphrase 7";

    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}");
    /**
     * An IPv4 address as a label would hold it: the whole value, or after a host name and before a
     * port ({@code host/127.0.0.1:6379}). A version such as {@code 21.0.12.1+1-LTS} does not match.
     */
    private static final Pattern IPV4_SHAPE = Pattern.compile("(?:^|[/\\s])\\d{1,3}(?:\\.\\d{1,3}){3}(?:$|[:/\\s])");

    /** The labels that may hold the Redis endpoint: those of the Lettuce client metrics. */
    private static final Set<String> INFRASTRUCTURE_ADDRESS_LABELS = Set.of("local", "remote");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private PrometheusMeterRegistry prometheus;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private OutboxDispatchWorker outboxWorker;

    @Autowired
    private DeliveryWorker deliveryWorker;

    private final Set<String> secrets = new LinkedHashSet<>();
    private final Set<String> identifiers = new LinkedHashSet<>();
    private IamTestClient iam;

    @Test
    @Verifies({"REQ-OBS-003", "REQ-OBS-005"})
    void noSecretEmailOrIdentifierReachesTheLogsOrTheMetricLabels(CapturedOutput output) throws Exception {
        iam = new IamTestClient(context, new ServerHttpObservationFilter(observationRegistry));
        List<UUID> users = runFlowThatIssuesEverySecret();
        addStoredHashes(users);

        String logs = output.getAll();
        List<String> logFindings = new ArrayList<>();
        for (String secret : secrets) {
            if (logs.contains(secret)) {
                logFindings.add("the logs contain " + describe(secret));
            }
        }

        List<Label> labels = labels(prometheus.scrape());
        List<String> labelFindings = new ArrayList<>();
        for (Label label : labels) {
            String value = label.value();
            for (String secret : secrets) {
                if (value.contains(secret)) {
                    labelFindings.add("a label contains " + describe(secret));
                }
            }
            if (label.isInfrastructureAddress()) {
                continue;
            }
            for (String identifier : identifiers) {
                if (value.contains(identifier)) {
                    labelFindings.add(label.where() + " contains the identifier " + identifier);
                }
            }
            if (UUID_SHAPE.matcher(value).find()
                    || EMAIL_SHAPE.matcher(value).find()
                    || IPV4_SHAPE.matcher(value).find()) {
                labelFindings.add(label.where() + " looks like an id, an e-mail or an address: " + value);
            }
        }

        System.out.println("FF-09: " + secrets.size() + " secrets searched in "
                + logs.lines().count() + " log lines; " + labels.size() + " label values scanned");
        assertThat(logs).as("the flow was logged").contains("Delivery ");
        assertThat(labels)
                .as("HTTP metrics use the route template, not the raw path")
                .contains(new Label(
                        "http_server_requests_seconds_count", "uri", API + "/workspaces/{workspaceId}/notes"));
        assertThat(logFindings).isEmpty();
        assertThat(labelFindings).isEmpty();
    }

    /** Every label of every series in a Prometheus text exposition. */
    static List<Label> labels(String scrape) {
        List<Label> labels = new ArrayList<>();
        for (String line : scrape.lines().toList()) {
            int open = line.indexOf('{');
            int close = line.lastIndexOf('}');
            if (line.startsWith("#") || open < 0 || close < open) {
                continue;
            }
            String metric = line.substring(0, open);
            Matcher label = LABEL.matcher(line.substring(open + 1, close));
            while (label.find()) {
                labels.add(new Label(metric, label.group(1), label.group(2)));
            }
        }
        return labels;
    }

    /** One label of one series. */
    record Label(String metric, String name, String value) {

        boolean isInfrastructureAddress() {
            return metric.startsWith("lettuce_") && INFRASTRUCTURE_ADDRESS_LABELS.contains(name);
        }

        String where() {
            return "label " + name + " of " + metric;
        }
    }

    /** The flow of the class comment; returns the ids of the two accounts it creates. */
    private List<UUID> runFlowThatIssuesEverySecret() throws Exception {
        secrets.add(PASSWORD);
        secrets.add(NEW_PASSWORD);
        identifiers.add("127.0.0.1");

        String ownerEmail = iam.registerNewAccount();
        secrets.add(ownerEmail);
        UUID ownerId = iam.userIdOf(ownerEmail);
        identifiers.add(ownerId.toString());
        String verification = iam.latestVerificationToken(ownerId);
        secrets.add(verification);
        iam.postJson(API + "/auth/email/verify", Map.of("token", verification), null)
                .andExpect(status().isNoContent());

        iam.postJson(API + "/auth/login", Map.of("email", ownerEmail, "password", "not the password"), null)
                .andExpect(status().isUnauthorized());
        IamTestClient.Tokens first =
                tokens(iam.postJson(API + "/auth/login", Map.of("email", ownerEmail, "password", PASSWORD), null)
                        .andExpect(status().isOk()));
        IamTestClient.Tokens rotated =
                tokens(iam.postJson(API + "/auth/refresh", Map.of("refresh_token", first.refreshToken()), null)
                        .andExpect(status().isOk()));
        String access = rotated.accessToken();

        UUID workspaceId = UUID.fromString(JsonPath.read(
                iam.postJson(API + "/workspaces", Map.of("name", "Scanned telemetry"), access)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        identifiers.add(workspaceId.toString());
        UUID noteId = UUID.fromString(JsonPath.read(
                iam.postJson(
                                API + "/workspaces/" + workspaceId + "/notes",
                                Map.of("title", "Scanned note", "body", "Body"),
                                access)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        identifiers.add(noteId.toString());
        iam.get(API + "/workspaces/" + workspaceId + "/notes/" + noteId, access).andExpect(status().isOk());

        String inviteeEmail = iam.registerVerifiedAccount();
        secrets.add(inviteeEmail);
        UUID inviteeId = iam.userIdOf(inviteeEmail);
        identifiers.add(inviteeId.toString());
        UUID accepted = invite(workspaceId, inviteeEmail, access);
        String acceptedToken = invitationToken(accepted);
        secrets.add(acceptedToken);
        String inviteeAccess = tokens(
                        iam.postJson(API + "/auth/login", Map.of("email", inviteeEmail, "password", PASSWORD), null)
                                .andExpect(status().isOk()))
                .accessToken();
        iam.postJson(API + "/invitations/accept", Map.of("token", acceptedToken), inviteeAccess)
                .andExpect(status().isOk());
        iam.get(API + "/workspaces/" + workspaceId + "/members", inviteeAccess).andExpect(status().isOk());
        String pendingEmail = "pending-" + UUID.randomUUID() + "@mail.com";
        secrets.add(pendingEmail);
        secrets.add(invitationToken(invite(workspaceId, pendingEmail, access)));

        iam.postJson(API + "/auth/refresh", Map.of("refresh_token", first.refreshToken()), null)
                .andExpect(status().isUnauthorized());
        iam.postJson(API + "/auth/password/forgot", Map.of("email", ownerEmail), null)
                .andExpect(status().isAccepted());
        String reset = iam.latestPasswordResetToken(ownerId);
        secrets.add(reset);
        iam.postJson(API + "/auth/password/reset", Map.of("token", reset, "newPassword", NEW_PASSWORD), null)
                .andExpect(status().isNoContent());
        iam.postJson(API + "/auth/logout", Map.of(), inviteeAccess).andExpect(status().isNoContent());

        while (outboxWorker.runOnce() > 0) {
            // create the deliveries of every event of the flow
        }
        while (deliveryWorker.runOnce() > 0) {
            // render and send them, links included
        }
        return List.of(ownerId, inviteeId);
    }

    /** The tokens of a login or refresh response, which become secrets; their session id is an identifier. */
    private IamTestClient.Tokens tokens(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        IamTestClient.Tokens tokens =
                new IamTestClient.Tokens(JsonPath.read(body, "$.access_token"), JsonPath.read(body, "$.refresh_token"));
        secrets.add(tokens.accessToken());
        secrets.add(tokens.refreshToken());
        identifiers.add(IamTestClient.sessionIdOf(tokens.accessToken()).toString());
        return tokens;
    }

    private UUID invite(UUID workspaceId, String email, String accessToken) throws Exception {
        UUID invitation = UUID.fromString(JsonPath.read(
                iam.postJson(
                                API + "/workspaces/" + workspaceId + "/invitations",
                                Map.of("email", email, "role", "MEMBER"),
                                accessToken)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));
        identifiers.add(invitation.toString());
        return invitation;
    }

    /** Opens the sealed token of the invitation event, as the notification module will. */
    private String invitationToken(UUID invitationId) {
        Map<String, Object> secret =
                superuser().sql("""
                        SELECT payload -> 'secret' ->> 'ciphertext' AS ciphertext,
                               payload -> 'secret' ->> 'keyId' AS key_id,
                               payload -> 'secret' ->> 'expiresAt' AS expires_at
                        FROM outbox_event
                        WHERE type = 'workspace.MemberInvited' AND payload ->> 'invitationId' = ?
                        """).param(invitationId.toString()).query().singleRow();
        return secretCipher.open(new SealedSecret(
                (String) secret.get("ciphertext"), (String) secret.get("key_id"), Instant.parse((String)
                        secret.get("expires_at"))));
    }

    /** The password and token hashes stored for both accounts, in every encoding a leak could use. */
    private void addStoredHashes(List<UUID> users) {
        for (UUID userId : users) {
            superuser()
                    .sql("SELECT password_hash FROM user_account WHERE id = ?")
                    .param(userId)
                    .query(String.class)
                    .list()
                    .forEach(secrets::add);
            List<byte[]> hashes = new ArrayList<>();
            hashes.addAll(superuser().sql("""
                            SELECT r.token_hash FROM refresh_token r
                            JOIN auth_session s ON s.id = r.session_id
                            WHERE s.user_id = ?
                            """).param(userId).query(byte[].class).list());
            hashes.addAll(superuser()
                    .sql("SELECT token_hash FROM one_time_token WHERE user_id = ?")
                    .param(userId)
                    .query(byte[].class)
                    .list());
            hashes.addAll(superuser()
                    .sql("SELECT token_hash FROM invitation WHERE invited_by = ?")
                    .param(userId)
                    .query(byte[].class)
                    .list());
            for (byte[] hash : hashes) {
                secrets.add(HexFormat.of().formatHex(hash));
                secrets.add(Base64.getEncoder().encodeToString(hash));
                secrets.add(Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
            }
        }
    }

    /** Names the kind of secret without printing it. */
    private static String describe(String secret) {
        if (secret.startsWith("wot_")) {
            return "a one-time token";
        }
        if (secret.startsWith("wrt_")) {
            return "a refresh token";
        }
        if (secret.startsWith("eyJ")) {
            return "an access token";
        }
        if (secret.startsWith("$argon2")) {
            return "a password hash";
        }
        if (secret.contains("@")) {
            return "an e-mail address";
        }
        return secret.contains(" ") ? "a password" : "a token hash";
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
