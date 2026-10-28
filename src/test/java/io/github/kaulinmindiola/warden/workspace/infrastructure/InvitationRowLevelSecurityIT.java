package io.github.kaulinmindiola.warden.workspace.infrastructure;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * REQ-SEC-012 for invitations, raw JDBC as the application role: the token holder sees only the
 * invitation of its token and can only accept it; the workspace context sees only its own
 * invitations; without context nothing is visible. Privileges allow changing only the status.
 */
class InvitationRowLevelSecurityIT extends IntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private WebApplicationContext context;

    private HikariDataSource pool;
    private JdbcClient app;
    private TransactionTemplate transactions;
    private UUID ownerId;
    private UUID w;
    private UUID first;
    private UUID second;
    private UUID ofX;
    private byte[] firstHash;

    @BeforeEach
    void seedInvitationsInTwoWorkspaces() throws Exception {
        IamTestClient iam = new IamTestClient(context);
        String email = iam.registerVerifiedAccount();
        String token = iam.login(email);
        ownerId = iam.userIdOf(email);
        w = createWorkspace(iam, token, "W");
        UUID x = createWorkspace(iam, token, "X");
        firstHash = randomHash();
        first = seedInvitation(w, firstHash);
        second = seedInvitation(w, randomHash());
        ofX = seedInvitation(x, randomHash());

        pool = newApplicationRolePool("invitation-rls", 1);
        app = JdbcClient.create(pool);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    @AfterEach
    void closePool() {
        pool.close();
    }

    @Test
    @Verifies({"REQ-SEC-012", "REQ-FUNC-036"})
    void theHolderOfATokenSeesOnlyItsInvitationAndCanOnlyAcceptIt() {
        long visible = asHolder(
                firstHash,
                jdbc -> jdbc.sql("SELECT count(*) FROM invitation")
                        .query(Long.class)
                        .single());
        int otherAccepted = asHolder(
                firstHash,
                jdbc -> jdbc.sql("UPDATE invitation SET status = 'ACCEPTED' WHERE id = ?")
                        .param(second)
                        .update());

        assertThat(visible).as("invitations visible to the holder").isEqualTo(1);
        assertThat(otherAccepted)
                .as("another invitation accepted by the holder")
                .isZero();
        assertRejected(
                () -> asHolder(
                        firstHash,
                        jdbc -> jdbc.sql("UPDATE invitation SET status = 'REVOKED' WHERE id = ?")
                                .param(first)
                                .update()),
                "row-level security");
        assertRejected(
                () -> asHolder(
                        firstHash,
                        jdbc -> jdbc.sql("UPDATE invitation SET role = 'ADMIN', status = 'ACCEPTED' WHERE id = ?")
                                .param(first)
                                .update()),
                "permission denied");
        assertRejected(
                () -> asHolder(
                        firstHash,
                        jdbc -> jdbc.sql("DELETE FROM invitation WHERE id = ?")
                                .param(first)
                                .update()),
                "permission denied");
        assertRejected(
                () -> asHolder(
                        firstHash,
                        jdbc -> jdbc.sql("""
                                        INSERT INTO invitation (id, workspace_id, email_normalized, role, locale, token_hash,
                                                                status, invited_by, expires_at, created_at)
                                        VALUES (?, ?, 'planted@mail.com', 'ADMIN', 'es', ?, 'PENDING', ?,
                                                now() + interval '7 days', now())
                                        """)
                                .param(UUID.randomUUID())
                                .param(w)
                                .param(randomHash())
                                .param(ownerId)
                                .update()),
                "row-level security");

        int accepted = asHolder(
                firstHash,
                jdbc -> jdbc.sql("UPDATE invitation SET status = 'ACCEPTED' WHERE id = ?")
                        .param(first)
                        .update());

        assertThat(accepted).isEqualTo(1);
        assertThat(statusSeenBySuperuser(first)).isEqualTo("ACCEPTED");
        assertThat(statusSeenBySuperuser(second)).isEqualTo("PENDING");
        assertThat(statusSeenBySuperuser(ofX)).isEqualTo("PENDING");
    }

    @Test
    @Verifies("REQ-SEC-012")
    void theWorkspaceContextSeesOnlyItsInvitationsAndNoContextSeesNone() {
        long visibleInW = inWorkspace(
                w,
                jdbc -> jdbc.sql("SELECT count(*) FROM invitation WHERE id IN (?, ?, ?)")
                        .param(first)
                        .param(second)
                        .param(ofX)
                        .query(Long.class)
                        .single());
        int updatedXInW = inWorkspace(
                w,
                jdbc -> jdbc.sql("UPDATE invitation SET status = 'REVOKED' WHERE id = ?")
                        .param(ofX)
                        .update());
        long visibleWithoutContext = app.sql("SELECT count(*) FROM invitation WHERE id IN (?, ?, ?)")
                .param(first)
                .param(second)
                .param(ofX)
                .query(Long.class)
                .single();
        int updatedWithoutContext = app.sql("UPDATE invitation SET status = 'REVOKED' WHERE id = ?")
                .param(first)
                .update();

        assertThat(visibleInW).as("invitations of W and X visible in W").isEqualTo(2);
        assertThat(updatedXInW).as("invitation of X revoked in W").isZero();
        assertThat(visibleWithoutContext).isZero();
        assertThat(updatedWithoutContext).isZero();
        assertThat(statusSeenBySuperuser(ofX)).isEqualTo("PENDING");
        assertThat(statusSeenBySuperuser(first)).isEqualTo("PENDING");
    }

    /** Runs {@code work} in a transaction whose only context is the hash of a presented token. */
    private <T> T asHolder(byte[] tokenHash, Function<JdbcClient, T> work) {
        return transactions.execute(status -> {
            app.sql("SELECT set_config('app.invitation_token_hash', ?, true)")
                    .param(HexFormat.of().formatHex(tokenHash))
                    .query(String.class)
                    .single();
            return work.apply(app);
        });
    }

    /** Runs {@code work} in a transaction whose only context is the workspace setting. */
    private <T> T inWorkspace(UUID workspaceId, Function<JdbcClient, T> work) {
        return transactions.execute(status -> {
            app.sql("SELECT set_config('app.workspace_id', ?, true)")
                    .param(workspaceId.toString())
                    .query(String.class)
                    .single();
            return work.apply(app);
        });
    }

    private static void assertRejected(ThrowingCallable statement, String reason) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining(reason);
    }

    private UUID seedInvitation(UUID workspaceId, byte[] tokenHash) {
        UUID invitationId = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO invitation (id, workspace_id, email_normalized, role, locale, token_hash, status,
                                                invited_by, expires_at, created_at)
                        VALUES (?, ?, ?, 'MEMBER', 'es', ?, 'PENDING', ?, now() + interval '7 days', now())
                        """)
                .param(invitationId)
                .param(workspaceId)
                .param("guest-" + invitationId + "@mail.com")
                .param(tokenHash)
                .param(ownerId)
                .update();
        return invitationId;
    }

    private String statusSeenBySuperuser(UUID invitationId) {
        return superuser()
                .sql("SELECT status FROM invitation WHERE id = ?")
                .param(invitationId)
                .query(String.class)
                .single();
    }

    private static byte[] randomHash() {
        byte[] hash = new byte[32];
        RANDOM.nextBytes(hash);
        return hash;
    }

    private static UUID createWorkspace(IamTestClient iam, String token, String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), token)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Only for seeding and inspection: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
