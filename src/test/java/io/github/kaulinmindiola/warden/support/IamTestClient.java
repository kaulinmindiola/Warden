package io.github.kaulinmindiola.warden.support;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the IAM API through the full filter chain for integration tests: registers accounts,
 * logs in, calls authenticated endpoints and reads the sealed one-time tokens that the outbox
 * holds until notifications exist.
 *
 * <p>Request builders are called through {@link MockMvcRequestBuilders} explicitly: this class
 * declares its own {@code get} and {@code delete} methods, which would hide statically imported
 * ones.
 */
public final class IamTestClient {

    public static final String API = ApiWebConfiguration.API_PREFIX;
    public static final String PASSWORD = "correct horse battery staple";

    /** The tokens of a login or refresh response. */
    public record Tokens(String accessToken, String refreshToken) {}

    private final MockMvc mvc;
    private final JsonMapper jsonMapper;
    private final JdbcClient jdbc;
    private final SecretCipher secretCipher;

    public IamTestClient(WebApplicationContext context) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        this.jsonMapper = context.getBean(JsonMapper.class);
        this.jdbc = JdbcClient.create(context.getBean(DataSource.class));
        this.secretCipher = context.getBean(SecretCipher.class);
    }

    public MockMvc mvc() {
        return mvc;
    }

    /** Registers a new account with {@link #PASSWORD} and returns its e-mail. */
    public String registerNewAccount() throws Exception {
        String email = "it-" + UUID.randomUUID() + "@mail.com";
        postJson(API + "/auth/register", Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isAccepted());
        return email;
    }

    /** Registers a new account with {@link #PASSWORD}, verifies its e-mail and returns it. */
    public String registerVerifiedAccount() throws Exception {
        String email = registerNewAccount();
        postJson(API + "/auth/email/verify", Map.of("token", latestVerificationToken(userIdOf(email))), null)
                .andExpect(status().isNoContent());
        return email;
    }

    /** Logs in with {@link #PASSWORD} and returns the access token. */
    public String login(String email) throws Exception {
        return loginTokens(email, null).accessToken();
    }

    /** Logs in with {@link #PASSWORD} and the given User-Agent, and returns the access token. */
    public String login(String email, String userAgent) throws Exception {
        return loginTokens(email, userAgent).accessToken();
    }

    /** Logs in with {@link #PASSWORD} and returns both tokens. */
    public Tokens loginTokens(String email) throws Exception {
        return loginTokens(email, null);
    }

    /** Logs in with {@link #PASSWORD} and the given User-Agent, and returns both tokens. */
    public Tokens loginTokens(String email, String userAgent) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(API + "/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD)));
        if (userAgent != null) {
            request.header(HttpHeaders.USER_AGENT, userAgent);
        }
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Tokens(JsonPath.read(body, "$.access_token"), JsonPath.read(body, "$.refresh_token"));
    }

    /** The {@code sid} claim of an access token, read without validating it. */
    public static UUID sessionIdOf(String accessToken) {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(payload, "$.sid"));
    }

    public UUID userIdOf(String email) {
        return jdbc.sql("SELECT id FROM user_account WHERE email_normalized = ?")
                .param(email)
                .query(UUID.class)
                .single();
    }

    /** Opens the sealed token of the most recent verification event of the user. */
    public String latestVerificationToken(UUID userId) {
        return latestSealedToken("iam.EmailVerificationRequested", userId);
    }

    /** Opens the sealed token of the most recent password reset event of the user. */
    public String latestPasswordResetToken(UUID userId) {
        return latestSealedToken("iam.PasswordResetRequested", userId);
    }

    /**
     * Sends a JSON POST, with a Bearer token when {@code accessToken} is not null. Null values in
     * the body are sent as JSON nulls.
     */
    public ResultActions postJson(String path, Map<String, ?> body, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new HashMap<>(body)));
        return mvc.perform(withBearer(request, accessToken));
    }

    /** Sends a GET, with a Bearer token when {@code accessToken} is not null. */
    public ResultActions get(String path, String accessToken) throws Exception {
        return mvc.perform(withBearer(MockMvcRequestBuilders.get(path), accessToken));
    }

    /** Sends a DELETE, with a Bearer token when {@code accessToken} is not null. */
    public ResultActions delete(String path, String accessToken) throws Exception {
        return mvc.perform(withBearer(MockMvcRequestBuilders.delete(path), accessToken));
    }

    private String latestSealedToken(String eventType, UUID userId) {
        Map<String, Object> secret =
                jdbc.sql("""
                        SELECT payload -> 'secret' ->> 'ciphertext' AS ciphertext,
                               payload -> 'secret' ->> 'keyId' AS key_id,
                               payload -> 'secret' ->> 'expiresAt' AS expires_at
                        FROM outbox_event
                        WHERE type = ?
                          AND payload -> 'recipient' ->> 'userId' = ?
                        ORDER BY occurred_at DESC
                        LIMIT 1
                        """).param(eventType).param(userId.toString()).query().singleRow();
        return secretCipher.open(new SealedSecret(
                (String) secret.get("ciphertext"), (String) secret.get("key_id"), Instant.parse((String)
                        secret.get("expires_at"))));
    }

    private static MockHttpServletRequestBuilder withBearer(MockHttpServletRequestBuilder request, String accessToken) {
        if (accessToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return request;
    }
}
