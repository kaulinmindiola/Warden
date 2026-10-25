package io.github.kaulinmindiola.warden.support;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the IAM API through the full filter chain for integration tests: registers accounts,
 * logs in and reads the sealed one-time tokens that the outbox holds until notifications exist.
 */
public final class IamTestClient {

    public static final String API = ApiWebConfiguration.API_PREFIX;
    public static final String PASSWORD = "correct horse battery staple";

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

    /** Logs in with {@link #PASSWORD} and returns the access token. */
    public String login(String email) throws Exception {
        String body = postJson(API + "/auth/login", Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    public UUID userIdOf(String email) {
        return jdbc.sql("SELECT id FROM user_account WHERE email_normalized = ?")
                .param(email)
                .query(UUID.class)
                .single();
    }

    /** Opens the sealed token of the most recent verification event of the user. */
    public String latestVerificationToken(UUID userId) {
        Map<String, Object> secret =
                jdbc.sql("""
                        SELECT payload -> 'secret' ->> 'ciphertext' AS ciphertext,
                               payload -> 'secret' ->> 'keyId' AS key_id,
                               payload -> 'secret' ->> 'expiresAt' AS expires_at
                        FROM outbox_event
                        WHERE type = 'iam.EmailVerificationRequested'
                          AND payload -> 'recipient' ->> 'userId' = ?
                        ORDER BY occurred_at DESC
                        LIMIT 1
                        """).param(userId.toString()).query().singleRow();
        return secretCipher.open(new SealedSecret(
                (String) secret.get("ciphertext"), (String) secret.get("key_id"), Instant.parse((String)
                        secret.get("expires_at"))));
    }

    /**
     * Sends a JSON POST, with a Bearer token when {@code accessToken} is not null. Null values in
     * the body are sent as JSON nulls.
     */
    public ResultActions postJson(String path, Map<String, ?> body, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new HashMap<>(body)));
        if (accessToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return mvc.perform(request);
    }
}
