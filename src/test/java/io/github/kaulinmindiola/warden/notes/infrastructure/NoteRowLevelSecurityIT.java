package io.github.kaulinmindiola.warden.notes.infrastructure;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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
 * REQ-SEC-012, SDD V07 layer L3 on its own: raw JDBC as the application role, without the
 * authorization interceptor, the Hibernate filter or any workspace condition in the SQL. Only the
 * transaction-local {@code app.workspace_id} and the {@code note_tenant} policy stand between W and
 * X.
 */
class NoteRowLevelSecurityIT extends IntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private HikariDataSource pool;
    private JdbcClient app;
    private TransactionTemplate transactions;
    private UUID ownerId;
    private UUID w;
    private UUID x;
    private UUID noteOfW;
    private UUID noteOfX;

    @BeforeEach
    void seedTwoWorkspacesWithOneNoteEach() throws Exception {
        IamTestClient iam = new IamTestClient(context);
        String email = iam.registerVerifiedAccount();
        String token = iam.login(email);
        ownerId = iam.userIdOf(email);
        w = createWorkspace(iam, token, "W");
        x = createWorkspace(iam, token, "X");
        noteOfW = seedNote(w, "Note of W");
        noteOfX = seedNote(x, "Note of X");

        pool = newApplicationRolePool("note-rls", 1);
        app = JdbcClient.create(pool);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    @AfterEach
    void closePool() {
        pool.close();
    }

    @Test
    @Verifies("REQ-SEC-012")
    void inTheContextOfWTheRowsOfXCannotBeReadWrittenOrDeleted() {
        long visible = inWorkspace(
                w,
                jdbc -> jdbc.sql("SELECT count(*) FROM note WHERE id IN (?, ?)")
                        .param(noteOfW)
                        .param(noteOfX)
                        .query(Long.class)
                        .single());
        long readX = inWorkspace(
                w,
                jdbc -> jdbc.sql("SELECT count(*) FROM note WHERE id = ?")
                        .param(noteOfX)
                        .query(Long.class)
                        .single());
        int updatedX = inWorkspace(
                w,
                jdbc -> jdbc.sql("UPDATE note SET title = 'Hijacked' WHERE id = ?")
                        .param(noteOfX)
                        .update());
        int deletedX = inWorkspace(
                w,
                jdbc -> jdbc.sql("DELETE FROM note WHERE id = ?").param(noteOfX).update());

        assertThat(visible).as("notes of W and X visible in W").isEqualTo(1);
        assertThat(readX).as("note of X read in W").isZero();
        assertThat(updatedX).as("note of X updated in W").isZero();
        assertThat(deletedX).as("note of X deleted in W").isZero();
        assertThatThrownBy(() -> inWorkspace(
                        w,
                        jdbc -> jdbc.sql("""
                                        INSERT INTO note (id, workspace_id, title, body, author_id, version,
                                                          created_at, updated_at)
                                        VALUES (?, ?, 'Planted', 'Body', ?, 0, now(), now())
                                        """)
                                .param(UUID.randomUUID())
                                .param(x)
                                .param(ownerId)
                                .update()))
                .as("a note planted in X from the context of W")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThat(titleSeenBySuperuser(noteOfX)).isEqualTo("Note of X");
    }

    @Test
    @Verifies("REQ-SEC-012")
    void withoutContextNoRowIsVisibleOrWritable() {
        long visible = app.sql("SELECT count(*) FROM note WHERE id IN (?, ?)")
                .param(noteOfW)
                .param(noteOfX)
                .query(Long.class)
                .single();
        int updated = app.sql("UPDATE note SET title = 'Hijacked' WHERE id IN (?, ?)")
                .param(noteOfW)
                .param(noteOfX)
                .update();

        assertThat(visible).isZero();
        assertThat(updated).isZero();
        assertThat(titleSeenBySuperuser(noteOfW)).isEqualTo("Note of W");
    }

    /** Runs {@code work} in a transaction whose only tenant context is the workspace setting. */
    private <T> T inWorkspace(UUID workspaceId, Function<JdbcClient, T> work) {
        return transactions.execute(status -> {
            app.sql("SELECT set_config('app.workspace_id', ?, true)")
                    .param(workspaceId.toString())
                    .query(String.class)
                    .single();
            return work.apply(app);
        });
    }

    private UUID seedNote(UUID workspaceId, String title) {
        UUID noteId = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO note (id, workspace_id, title, body, author_id, version, created_at, updated_at)
                        VALUES (?, ?, ?, 'Body', ?, 0, now(), now())
                        """)
                .param(noteId)
                .param(workspaceId)
                .param(title)
                .param(ownerId)
                .update();
        return noteId;
    }

    private String titleSeenBySuperuser(UUID noteId) {
        return superuser()
                .sql("SELECT title FROM note WHERE id = ?")
                .param(noteId)
                .query(String.class)
                .single();
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
