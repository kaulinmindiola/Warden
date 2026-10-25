package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.kaulinmindiola.warden.iam.domain.EmailAddress;
import io.github.kaulinmindiola.warden.iam.domain.SupportedLocale;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.iam.infrastructure.BoundedPasswordHasher;
import io.github.kaulinmindiola.warden.iam.infrastructure.UserAccountRepository;
import io.github.kaulinmindiola.warden.shared.crypto.KeyMaterial;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** UC-03 through the full filter chain, against PostgreSQL. */
class LoginIT extends IntegrationTest {

    private static final String REGISTER = ApiWebConfiguration.API_PREFIX + "/auth/register";
    private static final String LOGIN = ApiWebConfiguration.API_PREFIX + "/auth/login";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String USER_AGENT = "Mozilla/5.0 (Warden LoginIT)";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private KeyMaterial keyMaterial;

    @Autowired
    private TokenHasher tokenHasher;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UuidV7Generator ids;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @Verifies({"REQ-FUNC-008", "REQ-INT-003", "REQ-INT-004"})
    void validCredentialsStartASessionAndReturnExactlyTheSpecifiedTokens() throws Exception {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        clock.setInstant(now);
        String email = registerAccount();
        UUID userId = userIdOf(email);

        MvcResult result = login(email, PASSWORD).andExpect(status().isOk()).andReturn();

        String body = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = jsonMapper.readValue(body, Map.class);
        assertThat(fields.keySet())
                .containsExactlyInAnyOrder(
                        "access_token", "token_type", "expires_in", "refresh_token", "refresh_expires_in");
        assertThat(fields.get("token_type")).isEqualTo("Bearer");
        assertThat(((Number) fields.get("expires_in")).longValue()).isEqualTo(600);
        assertThat(((Number) fields.get("refresh_expires_in")).longValue()).isEqualTo(604_800);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        SignedJWT jwt = SignedJWT.parse(JsonPath.read(body, "$.access_token"));
        assertThat(jwt.verify(new ECDSAVerifier(keyMaterial.signingKey().publicKey())))
                .isTrue();
        assertThat(jwt.getHeader().getKeyID())
                .isEqualTo(keyMaterial.signingKey().keyId());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "sid", "jti", "iat", "exp");
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(now);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(now.plusSeconds(600));

        UUID sessionId = UUID.fromString(claims.getStringClaim("sid"));
        Map<String, Object> session = jdbc().sql(
                        "SELECT user_id, absolute_expires_at, user_agent_summary, revoked_at FROM auth_session WHERE id = ?")
                .param(sessionId)
                .query()
                .singleRow();
        assertThat(session.get("user_id")).isEqualTo(userId);
        assertThat(instant(session.get("absolute_expires_at"))).isEqualTo(now.plus(Duration.ofDays(30)));
        assertThat(session.get("user_agent_summary")).isEqualTo(USER_AGENT);
        assertThat(session.get("revoked_at")).isNull();

        String refreshToken = JsonPath.read(body, "$.refresh_token");
        assertThat(refreshToken).startsWith("wrt_").hasSize(47);
        Map<String, Object> refresh = jdbc().sql(
                        "SELECT token_hash, expires_at, used_at FROM refresh_token WHERE session_id = ?")
                .param(sessionId)
                .query()
                .singleRow();
        assertThat((byte[]) refresh.get("token_hash")).isEqualTo(tokenHasher.sha256(refreshToken));
        assertThat(instant(refresh.get("expires_at"))).isEqualTo(now.plus(Duration.ofDays(7)));
        assertThat(refresh.get("used_at")).isNull();

        assertThat(auditOutcomes(userId)).containsExactly("SUCCESS");
    }

    @Test
    @Verifies("REQ-FUNC-009")
    void anUnknownEmailAndAWrongPasswordGetTheSame401() throws Exception {
        String email = registerAccount();

        MvcResult wrongPassword = login(email, "not the right passphrase")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:invalid-credentials"))
                .andReturn();
        MvcResult unknownEmail =
                login("nobody-" + UUID.randomUUID() + "@mail.com", PASSWORD).andReturn();

        assertIdenticalResponses(unknownEmail, wrongPassword);
        assertThat(auditOutcomes(userIdOf(email))).containsExactly("FAILURE");
    }

    @Test
    @Verifies("REQ-FUNC-008")
    void anUnverifiedAccountCanLogInAndEachLoginIsANewSession() throws Exception {
        String email = registerAccount();
        UUID userId = userIdOf(email);

        login(email, PASSWORD).andExpect(status().isOk());
        login(email, PASSWORD).andExpect(status().isOk());

        Object verifiedAt = jdbc().sql("SELECT email_verified_at FROM user_account WHERE id = ?")
                .param(userId)
                .query()
                .singleRow()
                .get("email_verified_at");
        long sessions = jdbc().sql("SELECT count(*) FROM auth_session WHERE user_id = ?")
                .param(userId)
                .query(Long.class)
                .single();
        assertThat(verifiedAt).isNull();
        assertThat(sessions).isEqualTo(2);
    }

    @Test
    @Verifies("REQ-SEC-003")
    void aHashWithWeakerParametersIsReplacedAtTheNextLogin() throws Exception {
        String email = "rehash-" + UUID.randomUUID() + "@mail.com";
        BoundedPasswordHasher weakHasher = new BoundedPasswordHasher(new Argon2PasswordEncoder(16, 32, 1, 1024, 1), 1);
        UserAccount account = UserAccount.register(
                ids.next(), EmailAddress.of(email), weakHasher.hash(PASSWORD), SupportedLocale.ES, clock.instant());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> accounts.persist(account));

        login(email, PASSWORD).andExpect(status().isOk());

        String stored = jdbc().sql("SELECT password_hash FROM user_account WHERE id = ?")
                .param(account.id())
                .query(String.class)
                .single();
        assertThat(stored).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void malformedOrMissingCredentialsGetTheSame401AsAnUnknownEmail() throws Exception {
        MvcResult unknownEmail =
                login("nobody-" + UUID.randomUUID() + "@mail.com", PASSWORD).andReturn();

        assertIdenticalResponses(unknownEmail, login("not-an-email", PASSWORD).andReturn());
        assertIdenticalResponses(unknownEmail, login(null, PASSWORD).andReturn());
        assertIdenticalResponses(unknownEmail, login("ana@mail.com", null).andReturn());
    }

    private String registerAccount() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@mail.com";
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
        return email;
    }

    private ResultActions login(String email, String password) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        return mvc.perform(post(LOGIN)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private UUID userIdOf(String email) {
        return jdbc().sql("SELECT id FROM user_account WHERE email_normalized = ?")
                .param(email)
                .query(UUID.class)
                .single();
    }

    private java.util.List<String> auditOutcomes(UUID userId) {
        return jdbc().sql(
                        "SELECT outcome FROM audit_log WHERE action = 'LOGIN' AND actor_user_id = ? ORDER BY occurred_at")
                .param(userId)
                .query(String.class)
                .list();
    }

    private static Instant instant(Object timestamp) {
        if (timestamp instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        return ((java.sql.Timestamp) timestamp).toInstant();
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
