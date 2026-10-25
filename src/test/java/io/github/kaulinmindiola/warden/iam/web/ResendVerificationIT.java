package io.github.kaulinmindiola.warden.iam.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/** REQ-FUNC-007 (without the hourly limit, which arrives with rate limiting). */
class ResendVerificationIT extends IntegrationTest {

    private static final String RESEND = API + "/auth/email/verify/resend";
    private static final String VERIFY = API + "/auth/email/verify";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies("REQ-FUNC-007")
    void afterAResendThePreviousLinkIsRejectedAndTheNewOneVerifies() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        String accessToken = iam.login(email);
        String previousToken = iam.latestVerificationToken(userId);
        clock.advance(Duration.ofMinutes(1));

        iam.postJson(RESEND, Map.of(), accessToken).andExpect(status().isAccepted());

        String newToken = iam.latestVerificationToken(userId);
        assertThat(newToken).isNotEqualTo(previousToken);
        assertThat(verificationEvents(userId)).isEqualTo(2);
        assertThat(activeVerificationTokens(userId)).isEqualTo(1);

        iam.postJson(VERIFY, Map.of("token", previousToken), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:invalid-one-time-token"));
        iam.postJson(VERIFY, Map.of("token", newToken), null).andExpect(status().isNoContent());
    }

    @Test
    @Verifies("REQ-FUNC-007")
    void aVerifiedEmailGets409AndNoNewLink() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        iam.postJson(VERIFY, Map.of("token", iam.latestVerificationToken(userId)), null)
                .andExpect(status().isNoContent());
        String accessToken = iam.login(email);

        iam.postJson(RESEND, Map.of(), accessToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:email-already-verified"));

        assertThat(verificationEvents(userId)).isEqualTo(1);
    }

    private long verificationEvents(UUID userId) {
        return jdbc().sql("""
                        SELECT count(*) FROM outbox_event
                        WHERE type = 'iam.EmailVerificationRequested' AND payload -> 'recipient' ->> 'userId' = ?
                        """).param(userId.toString()).query(Long.class).single();
    }

    private long activeVerificationTokens(UUID userId) {
        return jdbc().sql("""
                        SELECT count(*) FROM one_time_token
                        WHERE user_id = ? AND purpose = 'EMAIL_VERIFICATION'
                          AND consumed_at IS NULL AND invalidated_at IS NULL
                        """).param(userId).query(Long.class).single();
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
