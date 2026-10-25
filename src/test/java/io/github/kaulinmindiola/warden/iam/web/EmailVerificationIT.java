package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/** UC-02 through the full filter chain, against PostgreSQL. */
class EmailVerificationIT extends IntegrationTest {

    private static final String REGISTER = ApiWebConfiguration.API_PREFIX + "/auth/register";
    private static final String VERIFY = ApiWebConfiguration.API_PREFIX + "/auth/email/verify";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String INVALID_TOKEN = "urn:warden:problem:invalid-one-time-token";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private SecureTokenGenerator tokenGenerator;

    private MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @Verifies({"REQ-FUNC-006", "BR-006"})
    void aValidTokenVerifiesTheEmailAndIsConsumed() throws Exception {
        Registered account = registerAccount();
        clock.advance(Duration.ofMinutes(5));
        Instant verifiedAt = clock.instant();

        MvcResult result = verify(account.token()).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(timestamp("SELECT email_verified_at FROM user_account WHERE id = ?", account.userId()))
                .isEqualTo(verifiedAt);
        assertThat(timestamp("SELECT consumed_at FROM one_time_token WHERE user_id = ?", account.userId()))
                .isEqualTo(verifiedAt);
    }

    @Test
    @Verifies("REQ-FUNC-006")
    void aReusedTokenGetsTheSameResponseAsAnUnknownOne() throws Exception {
        Registered account = registerAccount();
        verify(account.token()).andExpect(status().isNoContent());

        MvcResult reused = verify(account.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(INVALID_TOKEN))
                .andReturn();
        MvcResult unknown = verify(tokenGenerator.newOneTimeToken()).andReturn();

        assertIdenticalResponses(unknown, reused);
    }

    @Test
    @Verifies("REQ-FUNC-005")
    void aTokenIsValidJustBefore24HoursAndRejectedJustAfter() throws Exception {
        Registered stillValid = registerAccount();
        Registered expired = registerAccount();

        clock.advance(Duration.ofHours(24).minusSeconds(1));
        verify(stillValid.token()).andExpect(status().isNoContent());

        clock.advance(Duration.ofSeconds(2));
        MvcResult afterExpiry = verify(expired.token()).andReturn();
        MvcResult unknown = verify(tokenGenerator.newOneTimeToken()).andReturn();

        assertThat(afterExpiry.getResponse().getStatus()).isEqualTo(400);
        assertIdenticalResponses(unknown, afterExpiry);
        assertThat(timestamp("SELECT email_verified_at FROM user_account WHERE id = ?", expired.userId()))
                .isNull();
    }

    @Test
    void missingAndMalformedTokensGetTheSameResponse() throws Exception {
        MvcResult unknown = verify(tokenGenerator.newOneTimeToken()).andReturn();

        for (String token : new String[] {null, "", "not-a-token", "wrt_" + "a".repeat(43)}) {
            MvcResult malformed = verify(token).andReturn();
            assertIdenticalResponses(unknown, malformed);
        }
    }

    @Test
    @Verifies("REQ-SEC-004")
    void theTokenValueIsStoredInNoColumn() throws Exception {
        Registered account = registerAccount();
        verify(account.token()).andExpect(status().isNoContent());

        List<String> scanned = new ArrayList<>();
        List<String> matches = new ArrayList<>();
        List<Map<String, Object>> columns = jdbc().sql("""
                        SELECT c.table_name, c.column_name, c.data_type
                        FROM information_schema.columns c
                        JOIN pg_tables t ON t.schemaname = c.table_schema AND t.tablename = c.table_name
                        WHERE c.table_schema = 'public'
                          AND t.tableowner = 'warden_owner'
                          AND c.data_type IN ('text', 'character varying', 'jsonb', 'bytea')
                        """).query().listOfRows();
        for (Map<String, Object> column : columns) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            String predicate = "bytea".equals(column.get("data_type"))
                    ? "position(convert_to(?, 'UTF8') in \"%s\") > 0".formatted(name)
                    : "strpos(\"%s\"::text, ?) > 0".formatted(name);
            long found = jdbc().sql("SELECT count(*) FROM \"%s\" WHERE %s".formatted(table, predicate))
                    .param(account.token())
                    .query(Long.class)
                    .single();
            scanned.add(table + "." + name);
            if (found > 0) {
                matches.add(table + "." + name);
            }
        }

        assertThat(scanned).contains("one_time_token.token_hash", "outbox_event.payload", "user_account.password_hash");
        assertThat(matches).isEmpty();
    }

    private Registered registerAccount() throws Exception {
        String email = "verify-" + UUID.randomUUID() + "@mail.com";
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());

        UUID userId = jdbc().sql("SELECT id FROM user_account WHERE email_normalized = ?")
                .param(email)
                .query(UUID.class)
                .single();
        Map<String, Object> secret =
                jdbc().sql("""
                        SELECT payload -> 'secret' ->> 'ciphertext' AS ciphertext,
                               payload -> 'secret' ->> 'keyId' AS key_id,
                               payload -> 'secret' ->> 'expiresAt' AS expires_at
                        FROM outbox_event
                        WHERE type = 'iam.EmailVerificationRequested'
                          AND payload -> 'recipient' ->> 'userId' = ?
                        """).param(userId.toString()).query().singleRow();
        String token = secretCipher.open(new SealedSecret(
                (String) secret.get("ciphertext"), (String) secret.get("key_id"), Instant.parse((String)
                        secret.get("expires_at"))));
        return new Registered(userId, token);
    }

    private ResultActions verify(String token) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        return mvc.perform(
                post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body)));
    }

    /**
     * Reads one nullable timestamp column. The row mapper returns an {@link Optional} because
     * {@code single()} rejects a {@code null} result value.
     */
    private Instant timestamp(String sql, UUID userId) {
        return jdbc().sql(sql)
                .param(userId)
                .query((rs, rowNum) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class)))
                .single()
                .map(OffsetDateTime::toInstant)
                .orElse(null);
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    private record Registered(UUID userId, String token) {}
}
