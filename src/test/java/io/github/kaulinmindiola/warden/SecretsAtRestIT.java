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
import io.github.kaulinmindiola.warden.support.IamTestClient.Tokens;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * REQ-SEC-004 and the first half of REQ-SEC-005: after running every flow that issues secrets,
 * none of them is stored in plain text in any column, and the outbox carries link tokens only
 * sealed. Columns are discovered from the catalogue, so new tables are scanned automatically.
 */
class SecretsAtRestIT extends IntegrationTest {

    private static final String NEW_PASSWORD = "a brand new passphrase 42";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretCipher secretCipher;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies("REQ-SEC-004")
    void noIssuedSecretIsStoredInPlainTextInAnyColumn() throws Exception {
        Issued issued = runEveryFlowThatIssuesSecrets();

        List<String> scanned = new ArrayList<>();
        List<String> matches = new ArrayList<>();
        for (Map<String, Object> column : owner().sql("""
                        SELECT c.table_name, c.column_name, c.data_type
                        FROM information_schema.columns c
                        JOIN pg_tables t ON t.schemaname = c.table_schema AND t.tablename = c.table_name
                        WHERE c.table_schema = 'public'
                          AND t.tableowner = 'warden_owner'
                          AND c.data_type IN ('text', 'character varying', 'jsonb', 'bytea')
                        ORDER BY c.table_name, c.column_name
                        """).query().listOfRows()) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            boolean binary = "bytea".equals(column.get("data_type"));
            scanned.add(table + "." + name);
            for (String secret : issued.secrets()) {
                if (containsText(table, name, binary, secret)) {
                    matches.add(table + "." + name + " contains " + describe(secret));
                }
                byte[] raw = rawTokenBytes(secret);
                if (binary && raw != null && containsBytes(table, name, raw)) {
                    matches.add(table + "." + name + " contains the raw bytes of " + describe(secret));
                }
            }
        }

        System.out.println("REQ-SEC-004: " + issued.secrets().size() + " secrets searched in " + scanned.size()
                + " columns: " + scanned);
        assertThat(issued.secrets()).hasSize(14);
        assertThat(scanned)
                .contains(
                        "one_time_token.token_hash",
                        "refresh_token.token_hash",
                        "invitation.token_hash",
                        "outbox_event.payload",
                        "audit_log.details",
                        "user_account.password_hash",
                        "auth_session.user_agent_summary");
        assertThat(matches).isEmpty();
    }

    @Test
    @Verifies("REQ-SEC-005")
    void theOutboxCarriesLinkTokensOnlySealed() throws Exception {
        Issued issued = runEveryFlowThatIssuesSecrets();

        List<Map<String, Object>> sealed = owner().sql("""
                        SELECT type,
                               payload -> 'secret' ->> 'ciphertext' AS ciphertext,
                               payload -> 'secret' ->> 'keyId' AS key_id,
                               payload -> 'secret' ->> 'expiresAt' AS expires_at
                        FROM outbox_event
                        WHERE jsonb_exists(payload, 'secret')
                          AND (payload -> 'recipient' ->> 'userId' = ?
                               OR payload ->> 'invitationId' = ?)
                        ORDER BY dispatch_order
                        """)
                .param(issued.userId().toString())
                .param(issued.invitationId().toString())
                .query()
                .listOfRows();

        Set<String> opened = new LinkedHashSet<>();
        for (Map<String, Object> row : sealed) {
            String ciphertext = (String) row.get("ciphertext");
            assertThat(ciphertext).doesNotContain(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX);
            opened.add(secretCipher.open(new SealedSecret(
                    ciphertext, (String) row.get("key_id"), Instant.parse((String) row.get("expires_at")))));
        }

        assertThat(sealed)
                .extracting(row -> row.get("type"))
                .containsExactly(
                        "iam.EmailVerificationRequested",
                        "iam.EmailVerificationRequested",
                        "workspace.MemberInvited",
                        "iam.PasswordResetRequested",
                        "iam.PasswordResetRequested");
        assertThat(opened).containsExactlyElementsOf(issued.linkTokens());
    }

    /**
     * Registration (twice), login, resending and confirming the verification, refresh, refresh
     * reuse, a second login, an invitation accepted by another account, two reset requests and
     * the reset itself.
     */
    private Issued runEveryFlowThatIssuesSecrets() throws Exception {
        Set<String> linkTokens = new LinkedHashSet<>();
        List<String> secrets = new ArrayList<>(List.of(PASSWORD, NEW_PASSWORD));

        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        linkTokens.add(iam.latestVerificationToken(userId));
        iam.postJson(API + "/auth/register", Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isAccepted());

        Tokens firstLogin = iam.loginTokens(email);
        clock.advance(Duration.ofMinutes(1));
        iam.postJson(API + "/auth/email/verify/resend", Map.of(), firstLogin.accessToken())
                .andExpect(status().isAccepted());
        String verification = iam.latestVerificationToken(userId);
        linkTokens.add(verification);
        iam.postJson(API + "/auth/email/verify", Map.of("token", verification), null)
                .andExpect(status().isNoContent());

        Tokens rotated = refresh(firstLogin.refreshToken());
        iam.postJson(API + "/auth/refresh", Map.of("refresh_token", firstLogin.refreshToken()), null)
                .andExpect(status().isUnauthorized());
        Tokens secondLogin = iam.loginTokens(email);

        Invited invited = inviteAnotherAccountAndAccept(secondLogin.accessToken());
        linkTokens.add(invited.invitationToken());
        secrets.add(invited.inviteeAccessToken());

        iam.postJson(API + "/auth/password/forgot", Map.of("email", email), null)
                .andExpect(status().isAccepted());
        linkTokens.add(iam.latestPasswordResetToken(userId));
        clock.advance(Duration.ofMinutes(1));
        iam.postJson(API + "/auth/password/forgot", Map.of("email", email), null)
                .andExpect(status().isAccepted());
        String reset = iam.latestPasswordResetToken(userId);
        linkTokens.add(reset);
        iam.postJson(API + "/auth/password/reset", Map.of("token", reset, "newPassword", NEW_PASSWORD), null)
                .andExpect(status().isNoContent());

        secrets.addAll(linkTokens);
        for (Tokens tokens : List.of(firstLogin, rotated, secondLogin)) {
            secrets.add(tokens.refreshToken());
            secrets.add(tokens.accessToken());
        }
        assertThat(linkTokens).hasSize(5);
        return new Issued(userId, invited.invitationId(), linkTokens, secrets);
    }

    /**
     * The verified user creates a workspace and invites another verified account, which accepts
     * with the token from the sealed event (UC-12, UC-14).
     */
    private Invited inviteAnotherAccountAndAccept(String ownerAccessToken) throws Exception {
        String workspace = iam.postJson(API + "/workspaces", Map.of("name", "Secrets"), ownerAccessToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String workspaceId = JsonPath.read(workspace, "$.id");
        String inviteeEmail = iam.registerVerifiedAccount();
        String invitation = iam.postJson(
                        API + "/workspaces/" + workspaceId + "/invitations",
                        Map.of("email", inviteeEmail, "role", "MEMBER"),
                        ownerAccessToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID invitationId = UUID.fromString(JsonPath.read(invitation, "$.id"));
        String invitationToken = sealedInvitationToken(invitationId);
        String inviteeAccessToken = iam.login(inviteeEmail);
        iam.postJson(API + "/invitations/accept", Map.of("token", invitationToken), inviteeAccessToken)
                .andExpect(status().isOk());
        return new Invited(invitationId, invitationToken, inviteeAccessToken);
    }

    private String sealedInvitationToken(UUID invitationId) {
        Map<String, Object> secret =
                owner().sql("""
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

    private Tokens refresh(String refreshToken) throws Exception {
        String body = iam.postJson(API + "/auth/refresh", Map.of("refresh_token", refreshToken), null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Tokens(JsonPath.read(body, "$.access_token"), JsonPath.read(body, "$.refresh_token"));
    }

    /**
     * Whether the column contains the secret as text. For prefixed tokens only the random part is
     * searched, so a token stored without its prefix is found too.
     */
    private boolean containsText(String table, String column, boolean binary, String secret) {
        String predicate = binary
                ? "position(convert_to(?, 'UTF8') in \"%s\") > 0".formatted(column)
                : "strpos(\"%s\"::text, ?) > 0".formatted(column);
        return owner().sql("SELECT count(*) FROM \"%s\" WHERE %s".formatted(table, predicate))
                        .param(searchableText(secret))
                        .query(Long.class)
                        .single()
                > 0;
    }

    private boolean containsBytes(String table, String column, byte[] raw) {
        return owner().sql("SELECT count(*) FROM \"%s\" WHERE position(? in \"%s\") > 0".formatted(table, column))
                        .param(raw)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private static String searchableText(String secret) {
        return isPrefixedToken(secret) ? secret.substring(4) : secret;
    }

    /** The 32 random bytes behind a prefixed token, or {@code null} for other secrets. */
    private static byte[] rawTokenBytes(String secret) {
        return isPrefixedToken(secret) ? Base64.getUrlDecoder().decode(secret.substring(4)) : null;
    }

    private static boolean isPrefixedToken(String secret) {
        return secret.startsWith(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)
                || secret.startsWith(SecureTokenGenerator.REFRESH_TOKEN_PREFIX);
    }

    /** Names the kind of secret without printing it. */
    private static String describe(String secret) {
        if (secret.startsWith(SecureTokenGenerator.ONE_TIME_TOKEN_PREFIX)) {
            return "a one-time token";
        }
        if (secret.startsWith(SecureTokenGenerator.REFRESH_TOKEN_PREFIX)) {
            return "a refresh token";
        }
        return secret.startsWith("eyJ") ? "an access token" : "a password";
    }

    private static JdbcClient owner() {
        return JdbcClient.create(ownerDataSource());
    }

    private record Invited(UUID invitationId, String invitationToken, String inviteeAccessToken) {}

    private record Issued(UUID userId, UUID invitationId, Set<String> linkTokens, List<String> secrets) {}
}
