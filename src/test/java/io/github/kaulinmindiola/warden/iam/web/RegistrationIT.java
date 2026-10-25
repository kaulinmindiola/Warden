package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.crypto.TokenHasher;
import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** UC-01 through the full filter chain, against PostgreSQL. */
class RegistrationIT extends IntegrationTest {

    private static final String REGISTER = ApiWebConfiguration.API_PREFIX + "/auth/register";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String VALIDATION_ERROR = "urn:warden:problem:validation-error";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private TokenHasher tokenHasher;

    private MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @Verifies("REQ-FUNC-001")
    void aNewEmailCreatesAnUnverifiedAccountAndAVerificationEvent() throws Exception {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        clock.setInstant(now);
        String email = uniqueEmail("new");

        MvcResult result = register(email, PASSWORD, "en").andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getContentAsString()).isEmpty();

        UUID userId = jdbc().sql("SELECT id FROM user_account WHERE email_normalized = ?")
                .param(email)
                .query(UUID.class)
                .single();
        Map<String, Object> account = jdbc().sql(
                        "SELECT email_verified_at, registration_locale FROM user_account WHERE id = ?")
                .param(userId)
                .query()
                .singleRow();
        assertThat(account.get("email_verified_at")).isNull();
        assertThat(account.get("registration_locale")).isEqualTo("EN");

        byte[] storedHash = jdbc().sql("""
                        SELECT token_hash FROM one_time_token
                        WHERE user_id = ? AND purpose = 'EMAIL_VERIFICATION'
                          AND consumed_at IS NULL AND invalidated_at IS NULL
                        """).param(userId).query(byte[].class).single();
        OffsetDateTime tokenExpiry = jdbc().sql("SELECT expires_at FROM one_time_token WHERE user_id = ?")
                .param(userId)
                .query(OffsetDateTime.class)
                .single();
        assertThat(tokenExpiry.toInstant()).isEqualTo(now.plus(Duration.ofHours(24)));

        Map<String, Object> event =
                jdbc().sql("""
                        SELECT payload::text AS payload,
                               payload -> 'recipient' ->> 'email' AS email,
                               payload -> 'recipient' ->> 'fallbackLocale' AS locale,
                               payload -> 'secret' ->> 'ciphertext' AS ciphertext,
                               payload -> 'secret' ->> 'keyId' AS key_id,
                               payload -> 'secret' ->> 'expiresAt' AS secret_expires_at
                        FROM outbox_event
                        WHERE type = 'iam.EmailVerificationRequested'
                          AND payload -> 'recipient' ->> 'userId' = ?
                        """).param(userId.toString()).query().singleRow();
        assertThat(event.get("email")).isEqualTo(email);
        assertThat(event.get("locale")).isEqualTo("en");
        assertThat((String) event.get("payload")).doesNotContain("wot_");

        SealedSecret secret =
                new SealedSecret((String) event.get("ciphertext"), (String) event.get("key_id"), Instant.parse((String)
                        event.get("secret_expires_at")));
        String token = secretCipher.open(secret);
        assertThat(token).startsWith("wot_");
        assertThat(tokenHasher.sha256(token)).isEqualTo(storedHash);
    }

    @Test
    @Verifies({"REQ-FUNC-002", "REQ-FUNC-004"})
    void anExistingEmailGetsAnIdenticalResponseNoSecondAccountAndANoticeForTheHolder() throws Exception {
        String email = uniqueEmail("dup");

        MvcResult forNewEmail = register(email, PASSWORD, "es").andReturn();
        MvcResult forExistingEmail = register(
                        "  " + email.toUpperCase(Locale.ROOT) + " ", "another long passphrase", "en")
                .andReturn();

        assertIdenticalResponses(forNewEmail, forExistingEmail);
        UUID holder = jdbc().sql("SELECT id FROM user_account WHERE email_normalized = ?")
                .param(email)
                .query(UUID.class)
                .single();
        assertThat(eventsOf("iam.EmailVerificationRequested", holder)).isEqualTo(1);
        assertThat(eventsOf("iam.RegistrationAttemptedOnExistingAccount", holder))
                .isEqualTo(1);
        String noticeLocale =
                jdbc().sql("""
                        SELECT payload -> 'recipient' ->> 'fallbackLocale' FROM outbox_event
                        WHERE type = 'iam.RegistrationAttemptedOnExistingAccount'
                          AND payload -> 'recipient' ->> 'userId' = ?
                        """).param(holder.toString()).query(String.class).single();
        assertThat(noticeLocale).isEqualTo("es");
    }

    @Test
    @Verifies("REQ-FUNC-003")
    void weakPasswordsAreRejectedNamingTheField() throws Exception {
        String email = uniqueEmail("weak");

        register(email, "abcdefghijk", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].reason").value("must be between 12 and 128 characters"));
        register(email, "Password1234", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].reason").value("is too common"));

        assertThat(accountsWith(email)).isZero();
    }

    @Test
    @Verifies("REQ-FUNC-051")
    void theLocaleMustBeSpanishOrEnglishAndDefaultsToSpanish() throws Exception {
        register(uniqueEmail("fr"), PASSWORD, "fr")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("locale"))
                .andExpect(jsonPath("$.errors[0].reason").value("must be one of: es, en"));

        String email = uniqueEmail("default");
        register(email, PASSWORD, null).andExpect(status().isAccepted());

        String locale = jdbc().sql("SELECT registration_locale FROM user_account WHERE email_normalized = ?")
                .param(email)
                .query(String.class)
                .single();
        assertThat(locale).isEqualTo("ES");
    }

    @Test
    void everyInvalidFieldIsReportedTogether() throws Exception {
        register("not-an-email", null, "de")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].reason").value("must be a well-formed e-mail address"))
                .andExpect(jsonPath("$.errors[1].field").value("password"))
                .andExpect(jsonPath("$.errors[1].reason").value("is required"))
                .andExpect(jsonPath("$.errors[2].field").value("locale"));
    }

    private ResultActions register(String email, String password, String locale) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        if (locale != null) {
            body.put("locale", locale);
        }
        return mvc.perform(
                post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)));
    }

    private long eventsOf(String type, UUID userId) {
        return jdbc().sql(
                        "SELECT count(*) FROM outbox_event WHERE type = ? AND payload -> 'recipient' ->> 'userId' = ?")
                .param(type)
                .param(userId.toString())
                .query(Long.class)
                .single();
    }

    private long accountsWith(String email) {
        return jdbc().sql("SELECT count(*) FROM user_account WHERE email_normalized = ?")
                .param(email)
                .query(Long.class)
                .single();
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@mail.com";
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
