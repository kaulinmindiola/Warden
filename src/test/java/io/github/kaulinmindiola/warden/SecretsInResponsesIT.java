package io.github.kaulinmindiola.warden;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * REQ-SEC-014 and fitness function FF-10: no API response contains a password, a password hash, a
 * token hash, a one-time token, or a refresh or access token other than the ones that response
 * issues.
 *
 * <p>A flow issues every kind of secret: registration and verification, login, refresh, a
 * workspace with a note, an accepted and a pending invitation, and a password reset. Every
 * response of the flow is recorded, the writes included, and then every read endpoint is called.
 * The secrets are searched in the status line, the headers and the body of each response; hashes
 * are searched in hexadecimal and in both Base64 alphabets. Rendered notification content does not
 * exist until phase 4.
 *
 * <p>{@link #READS} must list every GET endpoint of the API, which the handler mapping checks, so
 * a new read endpoint fails the build until it is scanned.
 */
class SecretsInResponsesIT extends IntegrationTest {

    private static final String NEW_PASSWORD = "a brand new passphrase 42";

    /** Every GET endpoint of the API, by pattern, with the path to call in the flow. */
    static final Map<String, Function<Ids, String>> READS = new LinkedHashMap<>();

    static {
        READS.put(API + "/auth/sessions", ids -> API + "/auth/sessions");
        READS.put(API + "/workspaces", ids -> API + "/workspaces");
        READS.put(API + "/workspaces/{workspaceId}", ids -> API + "/workspaces/" + ids.workspaceId());
        READS.put(
                API + "/workspaces/{workspaceId}/members",
                ids -> API + "/workspaces/" + ids.workspaceId() + "/members");
        READS.put(
                API + "/workspaces/{workspaceId}/invitations",
                ids -> API + "/workspaces/" + ids.workspaceId() + "/invitations");
        READS.put(API + "/workspaces/{workspaceId}/notes", ids -> API + "/workspaces/" + ids.workspaceId() + "/notes");
        READS.put(
                API + "/workspaces/{workspaceId}/notes/{noteId}",
                ids -> API + "/workspaces/" + ids.workspaceId() + "/notes/" + ids.noteId());
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private IamTestClient iam;
    private final List<Recorded> responses = new ArrayList<>();
    private final Set<String> secrets = new LinkedHashSet<>();
    private String ownerEmail;
    private UUID ownerId;
    private UUID inviteeId;
    private String lastIssuedRefreshToken;

    @BeforeEach
    void setUp() {
        iam = new IamTestClient(context);
        responses.clear();
        secrets.clear();
    }

    @Test
    @Verifies("REQ-SEC-014")
    void everyGetEndpointOfTheApiIsScanned() {
        Set<String> getEndpoints = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, ?> handler :
                handlerMapping.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = handler.getKey().getMethodsCondition().getMethods();
            if (methods.isEmpty() || methods.contains(RequestMethod.GET)) {
                handler.getKey().getPatternValues().stream()
                        .filter(pattern -> pattern.startsWith(API + "/"))
                        .forEach(getEndpoints::add);
            }
        }

        assertThat(getEndpoints).containsExactlyInAnyOrderElementsOf(READS.keySet());
    }

    @Test
    @Verifies("REQ-SEC-014")
    void noResponseContainsASecretItDidNotIssue() throws Exception {
        Ids ids = runFlowThatIssuesEverySecret();
        String reader = loginWith(iam.registerVerifiedAccount(), PASSWORD);
        String owner = loginWith(ownerEmail, NEW_PASSWORD);
        for (Map.Entry<String, Function<Ids, String>> read : READS.entrySet()) {
            String path = read.getValue().apply(ids);
            record("GET " + path, iam.get(path, owner).andExpect(status().isOk()));
        }
        record(
                "GET " + API + "/workspaces as another user",
                iam.get(API + "/workspaces", reader).andExpect(status().isOk()));
        addStoredHashes();

        List<String> findings = new ArrayList<>();
        for (Recorded response : responses) {
            for (String secret : secrets) {
                if (!response.issued().contains(secret) && response.text().contains(secret)) {
                    findings.add(response.label() + " contains " + describe(secret));
                }
            }
            if (response.text().contains("$argon2")) {
                findings.add(response.label() + " contains a password hash");
            }
            if (response.text().contains(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)) {
                findings.add(response.label() + " contains a one-time token");
            }
            if (response.issued().isEmpty()
                    && (response.text().contains(SecureTokenGenerator.REFRESH_TOKEN_PREFIX)
                            || response.text().contains("eyJ"))) {
                findings.add(response.label() + " contains a token although it issues none");
            }
        }

        System.out.println(
                "REQ-SEC-014: " + secrets.size() + " secrets searched in " + responses.size() + " responses");
        assertThat(responses).hasSizeGreaterThanOrEqualTo(READS.size() + 10);
        assertThat(findings).isEmpty();
    }

    /**
     * Registration and verification, login, refresh, a workspace with a note, an invitation
     * accepted by another account, a pending invitation and a password reset, which revokes the
     * owner's sessions.
     */
    private Ids runFlowThatIssuesEverySecret() throws Exception {
        secrets.add(PASSWORD);
        secrets.add(NEW_PASSWORD);

        ownerEmail = iam.registerNewAccount();
        ownerId = iam.userIdOf(ownerEmail);
        String verification = iam.latestVerificationToken(ownerId);
        secrets.add(verification);
        record(
                "POST verify",
                iam.postJson(API + "/auth/email/verify", Map.of("token", verification), null)
                        .andExpect(status().isNoContent()));

        String firstAccess = loginWith(ownerEmail, PASSWORD);
        String firstRefresh = lastIssuedRefreshToken;
        String access = refresh(firstRefresh);
        String ownerRefresh = lastIssuedRefreshToken;

        String workspace = record(
                "POST workspaces",
                iam.postJson(API + "/workspaces", Map.of("name", "Scanned"), access)
                        .andExpect(status().isCreated()));
        UUID workspaceId = UUID.fromString(JsonPath.read(workspace, "$.id"));
        String note = record(
                "POST notes",
                iam.postJson(
                                API + "/workspaces/" + workspaceId + "/notes",
                                Map.of("title", "Scanned note", "body", "Body"),
                                access)
                        .andExpect(status().isCreated()));
        UUID noteId = UUID.fromString(JsonPath.read(note, "$.id"));

        String inviteeEmail = iam.registerVerifiedAccount();
        inviteeId = iam.userIdOf(inviteeEmail);
        UUID accepted = invite(workspaceId, inviteeEmail, access);
        String acceptedToken = invitationToken(accepted);
        secrets.add(acceptedToken);
        String inviteeAccess = loginWith(inviteeEmail, PASSWORD);
        record(
                "POST invitations/accept",
                iam.postJson(API + "/invitations/accept", Map.of("token", acceptedToken), inviteeAccess)
                        .andExpect(status().isOk()));
        UUID pending = invite(workspaceId, "pending-" + UUID.randomUUID() + "@mail.com", access);
        secrets.add(invitationToken(pending));

        record(
                "POST password/forgot",
                iam.postJson(API + "/auth/password/forgot", Map.of("email", ownerEmail), null)
                        .andExpect(status().isAccepted()));
        String reset = iam.latestPasswordResetToken(ownerId);
        secrets.add(reset);
        record(
                "POST password/reset",
                iam.postJson(API + "/auth/password/reset", Map.of("token", reset, "newPassword", NEW_PASSWORD), null)
                        .andExpect(status().isNoContent()));
        record(
                "POST refresh with a revoked session",
                iam.postJson(API + "/auth/refresh", Map.of("refresh_token", ownerRefresh), null)
                        .andExpect(status().isUnauthorized()));
        record("GET sessions with a revoked token", iam.get(API + "/auth/sessions", firstAccess));
        return new Ids(workspaceId, noteId);
    }

    /** Logs in through the API, records the response as issuing its tokens and returns the access token. */
    private String loginWith(String email, String password) throws Exception {
        return recordIssuing(
                "POST login",
                iam.postJson(API + "/auth/login", Map.of("email", email, "password", password), null)
                        .andExpect(status().isOk()));
    }

    private String refresh(String refreshToken) throws Exception {
        return recordIssuing(
                "POST refresh",
                iam.postJson(API + "/auth/refresh", Map.of("refresh_token", refreshToken), null)
                        .andExpect(status().isOk()));
    }

    private UUID invite(UUID workspaceId, String email, String accessToken) throws Exception {
        String body = record(
                "POST invitations",
                iam.postJson(
                                API + "/workspaces/" + workspaceId + "/invitations",
                                Map.of("email", email, "role", "MEMBER"),
                                accessToken)
                        .andExpect(status().isCreated()));
        return UUID.fromString(JsonPath.read(body, "$.id"));
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
    private void addStoredHashes() {
        for (UUID userId : List.of(ownerId, inviteeId)) {
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

    private String record(String label, ResultActions result) throws Exception {
        return record(label, result, Set.of());
    }

    /** Records a response that issues an access and a refresh token, which become secrets for every other response. */
    private String recordIssuing(String label, ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.access_token");
        String refresh = JsonPath.read(body, "$.refresh_token");
        secrets.add(access);
        secrets.add(refresh);
        lastIssuedRefreshToken = refresh;
        record(label, result, Set.of(access, refresh));
        return access;
    }

    private String record(String label, ResultActions result, Set<String> issued) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        StringBuilder text = new StringBuilder().append(response.getStatus()).append('\n');
        for (String name : response.getHeaderNames()) {
            text.append(name)
                    .append(": ")
                    .append(String.join(", ", response.getHeaders(name)))
                    .append('\n');
        }
        String body = response.getContentAsString();
        responses.add(new Recorded(label, text.append(body).toString(), issued));
        return body;
    }

    /** Names the kind of secret without printing it. */
    private static String describe(String secret) {
        if (secret.startsWith(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)) {
            return "a one-time token";
        }
        if (secret.startsWith(SecureTokenGenerator.REFRESH_TOKEN_PREFIX)) {
            return "a refresh token";
        }
        if (secret.startsWith("eyJ")) {
            return "an access token";
        }
        if (secret.startsWith("$argon2")) {
            return "a password hash";
        }
        return secret.contains(" ") ? "a password" : "a token hash";
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }

    record Ids(UUID workspaceId, UUID noteId) {}

    private record Recorded(String label, String text, Set<String> issued) {}
}
