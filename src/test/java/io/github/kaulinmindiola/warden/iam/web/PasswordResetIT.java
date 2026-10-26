package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static io.github.kaulinmindiola.warden.support.IamTestClient.sessionIdOf;
import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.shared.crypto.SecureTokenGenerator;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IamTestClient.Tokens;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/** UC-08 and UC-09 through the full filter chain, against PostgreSQL and Redis. */
class PasswordResetIT extends IntegrationTest {

    private static final String FORGOT = API + "/auth/password/forgot";
    private static final String RESET = API + "/auth/password/reset";
    private static final String LOGIN = API + "/auth/login";
    private static final String REFRESH = API + "/auth/refresh";
    private static final String SESSIONS = API + "/auth/sessions";
    private static final String NEW_PASSWORD = "a brand new passphrase 42";
    private static final String OTHER_PASSWORD = "yet another passphrase 99";
    private static final String INVALID_TOKEN = "urn:warden:problem:invalid-one-time-token";
    private static final String VALIDATION_ERROR = "urn:warden:problem:validation-error";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SecureTokenGenerator tokenGenerator;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies({"REQ-FUNC-019", "BR-007"})
    void existingAndUnknownEmailsGetTheSameResponseAndOnlyTheAccountGetsALink() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        String unknown = "nobody-" + UUID.randomUUID() + "@mail.com";

        MvcResult existing = forgot("  " + email.toUpperCase(Locale.ROOT)).andReturn();
        MvcResult missing = forgot(unknown).andReturn();

        assertThat(existing.getResponse().getStatus()).isEqualTo(202);
        assertThat(existing.getResponse().getContentAsString()).isEmpty();
        assertIdenticalResponses(existing, missing);
        assertThat(resetEventsFor(userId)).isEqualTo(1);
        assertThat(resetEventsTo(unknown)).isZero();
        assertThat(activeResetTokens(userId)).isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-FUNC-019", "BR-006"})
    void aSecondRequestInvalidatesTheFirstLink() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        forgot(email).andExpect(status().isAccepted());
        String first = iam.latestPasswordResetToken(userId);
        clock.advance(Duration.ofMinutes(1));

        forgot(email).andExpect(status().isAccepted());
        String second = iam.latestPasswordResetToken(userId);

        assertThat(second).isNotEqualTo(first);
        assertThat(activeResetTokens(userId)).isEqualTo(1);
        reset(first, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(INVALID_TOKEN));
        reset(second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    @Verifies({"REQ-FUNC-019", "REQ-FUNC-021", "BR-006"})
    void aLinkIsValidJustBefore30MinutesAndRejectedJustAfter() throws Exception {
        String stillValidEmail = iam.registerNewAccount();
        String expiredEmail = iam.registerNewAccount();
        UUID expiredUserId = iam.userIdOf(expiredEmail);
        forgot(stillValidEmail).andExpect(status().isAccepted());
        forgot(expiredEmail).andExpect(status().isAccepted());
        String stillValid = iam.latestPasswordResetToken(iam.userIdOf(stillValidEmail));
        String expired = iam.latestPasswordResetToken(expiredUserId);
        String hashBefore = passwordHash(expiredUserId);

        clock.advance(Duration.ofMinutes(30).minusSeconds(1));
        reset(stillValid, NEW_PASSWORD).andExpect(status().isNoContent());

        clock.advance(Duration.ofSeconds(2));
        MvcResult afterExpiry = reset(expired, NEW_PASSWORD).andReturn();
        MvcResult unknown =
                reset(tokenGenerator.newOneTimeToken(), NEW_PASSWORD).andReturn();

        assertThat(afterExpiry.getResponse().getStatus()).isEqualTo(400);
        assertIdenticalResponses(unknown, afterExpiry);
        assertThat(passwordHash(expiredUserId)).isEqualTo(hashBefore);
        login(expiredEmail, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @Verifies({"REQ-FUNC-020", "REQ-FUNC-018"})
    void aResetReplacesThePasswordRevokesEverySessionAndVerifiesTheEmail() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        Tokens first = iam.loginTokens(email);
        Tokens second = iam.loginTokens(email);
        assertThat(emailVerified(userId)).isFalse();
        forgot(email).andExpect(status().isAccepted());

        MvcResult result =
                reset(iam.latestPasswordResetToken(userId), NEW_PASSWORD).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        iam.get(SESSIONS, first.accessToken()).andExpect(status().isUnauthorized());
        iam.get(SESSIONS, second.accessToken()).andExpect(status().isUnauthorized());
        refresh(first.refreshToken()).andExpect(status().isUnauthorized());
        refresh(second.refreshToken()).andExpect(status().isUnauthorized());
        assertThat(revokeReason(sessionIdOf(first.accessToken()))).isEqualTo("PASSWORD_RESET");
        assertThat(revokeReason(sessionIdOf(second.accessToken()))).isEqualTo("PASSWORD_RESET");

        login(email, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:invalid-credentials"));
        login(email, NEW_PASSWORD).andExpect(status().isOk());

        assertThat(emailVerified(userId)).isTrue();
        assertThat(passwordChangedEvents(userId)).isEqualTo(1);
        assertThat(auditCount("PASSWORD_RESET", userId)).isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-FUNC-021", "BR-006"})
    void unknownUsedWrongPurposeAndMalformedTokensGetTheSameResponseAndChangeNothing() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        forgot(email).andExpect(status().isAccepted());
        String used = iam.latestPasswordResetToken(userId);
        reset(used, NEW_PASSWORD).andExpect(status().isNoContent());
        String hashAfterReset = passwordHash(userId);

        MvcResult unknown = reset(tokenGenerator.newOneTimeToken(), OTHER_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(INVALID_TOKEN))
                .andReturn();

        assertIdenticalResponses(unknown, reset(used, OTHER_PASSWORD).andReturn());
        assertIdenticalResponses(
                unknown,
                reset(iam.latestVerificationToken(userId), OTHER_PASSWORD).andReturn());
        assertIdenticalResponses(unknown, reset("not-a-token", OTHER_PASSWORD).andReturn());
        assertIdenticalResponses(unknown, reset(null, OTHER_PASSWORD).andReturn());
        assertThat(passwordHash(userId)).isEqualTo(hashAfterReset);
        assertThat(passwordChangedEvents(userId)).isEqualTo(1);
    }

    @Test
    @Verifies("REQ-FUNC-020")
    void aPasswordThatBreaksThePolicyIsRejectedAndTheLinkStaysUsable() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        forgot(email).andExpect(status().isAccepted());
        String token = iam.latestPasswordResetToken(userId);

        reset(token, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].reason").value("must be between 12 and 128 characters"));

        assertThat(activeResetTokens(userId)).isEqualTo(1);
        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void aMissingOrMalformedEmailIsAValidationErrorThatRevealsNothing() throws Exception {
        forgot(null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].reason").value("is required"));
        forgot("not-an-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].reason").value("must be a well-formed e-mail address"));
    }

    private ResultActions forgot(String email) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        return iam.postJson(FORGOT, body, null);
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        body.put("newPassword", newPassword);
        return iam.postJson(RESET, body, null);
    }

    private ResultActions login(String email, String password) throws Exception {
        return iam.postJson(LOGIN, Map.of("email", email, "password", password), null);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return iam.postJson(REFRESH, Map.of("refresh_token", refreshToken), null);
    }

    private long resetEventsFor(UUID userId) {
        return jdbc().sql("""
                        SELECT count(*) FROM outbox_event
                        WHERE type = 'iam.PasswordResetRequested' AND payload -> 'recipient' ->> 'userId' = ?
                        """).param(userId.toString()).query(Long.class).single();
    }

    private long resetEventsTo(String email) {
        return jdbc().sql("""
                        SELECT count(*) FROM outbox_event
                        WHERE type = 'iam.PasswordResetRequested' AND payload -> 'recipient' ->> 'email' = ?
                        """).param(email).query(Long.class).single();
    }

    private long passwordChangedEvents(UUID userId) {
        return jdbc().sql("""
                        SELECT count(*) FROM outbox_event
                        WHERE type = 'iam.PasswordChanged' AND payload -> 'recipient' ->> 'userId' = ?
                        """).param(userId.toString()).query(Long.class).single();
    }

    private long activeResetTokens(UUID userId) {
        return jdbc().sql("""
                        SELECT count(*) FROM one_time_token
                        WHERE user_id = ? AND purpose = 'PASSWORD_RESET'
                          AND consumed_at IS NULL AND invalidated_at IS NULL
                        """).param(userId).query(Long.class).single();
    }

    private String passwordHash(UUID userId) {
        return jdbc().sql("SELECT password_hash FROM user_account WHERE id = ?")
                .param(userId)
                .query(String.class)
                .single();
    }

    private boolean emailVerified(UUID userId) {
        return jdbc().sql("SELECT email_verified_at IS NOT NULL FROM user_account WHERE id = ?")
                .param(userId)
                .query(Boolean.class)
                .single();
    }

    private String revokeReason(UUID sessionId) {
        return (String) jdbc().sql("SELECT revoke_reason FROM auth_session WHERE id = ?")
                .param(sessionId)
                .query()
                .singleRow()
                .get("revoke_reason");
    }

    private long auditCount(String action, UUID userId) {
        return jdbc().sql("SELECT count(*) FROM audit_log WHERE action = ? AND actor_user_id = ?")
                .param(action)
                .param(userId)
                .query(Long.class)
                .single();
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
