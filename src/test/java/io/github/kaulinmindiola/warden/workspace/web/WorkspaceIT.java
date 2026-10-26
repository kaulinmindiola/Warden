package io.github.kaulinmindiola.warden.workspace.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** UC-10 and UC-11 through the full filter chain, against PostgreSQL with Row Level Security. */
class WorkspaceIT extends IntegrationTest {

    private static final String WORKSPACES = API + "/workspaces";
    private static final String VALIDATION_ERROR = "urn:warden:problem:validation-error";

    @Autowired
    private WebApplicationContext context;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    @Verifies({"REQ-FUNC-027", "BR-001"})
    void aVerifiedUserCreatesAWorkspaceAndIsItsOnlyOwner() throws Exception {
        String email = iam.registerVerifiedAccount();
        UUID userId = iam.userIdOf(email);

        MvcResult result = create(iam.login(email), "  Acme  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn();

        UUID workspaceId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
        assertThat(result.getResponse().getHeader("Location")).endsWith(WORKSPACES + "/" + workspaceId);
        assertThat(superuser()
                        .sql("SELECT user_id, role FROM workspace_member WHERE workspace_id = ?")
                        .param(workspaceId)
                        .query((rs, rowNum) -> tuple(rs.getObject("user_id", UUID.class), rs.getString("role")))
                        .list())
                .containsExactly(tuple(userId, "OWNER"));
        assertThat(superuser()
                        .sql("SELECT count(*) FROM audit_log WHERE action = 'WORKSPACE_CREATED' AND workspace_id = ?"
                                + " AND actor_user_id = ?")
                        .param(workspaceId)
                        .param(userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @Verifies({"REQ-FUNC-027", "BR-010"})
    void anUnverifiedUserGets403AndNothingIsCreated() throws Exception {
        String email = iam.registerNewAccount();

        create(iam.login(email), "Acme")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:email-not-verified"));

        assertThat(superuser()
                        .sql("SELECT count(*) FROM workspace WHERE created_by = ?")
                        .param(iam.userIdOf(email))
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @Verifies("REQ-FUNC-027")
    void theNameMustHaveBetween1And100Characters() throws Exception {
        String accessToken = iam.login(iam.registerVerifiedAccount());

        for (String invalid : new String[] {"", "   ", "a".repeat(101)}) {
            create(accessToken, invalid)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].reason").value("must be between 1 and 100 characters"));
        }
        create(accessToken, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].reason").value("is required"));
        create(accessToken, "a".repeat(100)).andExpect(status().isCreated());
    }

    @Test
    @Verifies("REQ-FUNC-028")
    void aUserSeesExactlyTheirNonDeletedWorkspacesWithTheirRoles() throws Exception {
        String ownerToken = iam.login(iam.registerVerifiedAccount());
        UUID w = createdId(ownerToken, "W");
        UUID x = createdId(ownerToken, "X");
        createdId(ownerToken, "Y");
        UUID deleted = createdId(ownerToken, "Z");
        String memberEmail = iam.registerNewAccount();
        UUID memberId = iam.userIdOf(memberEmail);
        addMember(w, memberId, "ADMIN");
        addMember(x, memberId, "VIEWER");
        addMember(deleted, memberId, "MEMBER");
        superuser()
                .sql("UPDATE workspace SET deleted_at = now() WHERE id = ?")
                .param(deleted)
                .update();

        String body = iam.get(WORKSPACES, iam.login(memberEmail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Map<String, Object>> items = JsonPath.read(body, "$.items");
        assertThat(items)
                .extracting(item -> item.get("id"), item -> item.get("name"), item -> item.get("role"))
                .containsExactlyInAnyOrder(tuple(w.toString(), "W", "ADMIN"), tuple(x.toString(), "X", "VIEWER"));
    }

    @Test
    @Verifies({"REQ-FUNC-028", "REQ-INT-006"})
    void theListIsPaginatedOldestFirst() throws Exception {
        String accessToken = iam.login(iam.registerVerifiedAccount());
        for (String name : List.of("A", "B", "C")) {
            createdId(accessToken, name);
            clock.advance(Duration.ofSeconds(1));
        }

        iam.get(WORKSPACES + "?size=2", accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name").value(org.hamcrest.Matchers.contains("A", "B")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        iam.get(WORKSPACES + "?page=1&size=2", accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name").value(org.hamcrest.Matchers.contains("C")));
    }

    @Test
    @Verifies("REQ-SEC-012")
    void membershipsAreInvisibleToTheApplicationRoleOutsideTheirContext() throws Exception {
        String email = iam.registerVerifiedAccount();
        UUID userId = iam.userIdOf(email);
        UUID workspaceId = createdId(iam.login(email), "Private");
        String countMembers = "SELECT count(*) FROM workspace_member WHERE workspace_id = ?";

        try (HikariDataSource pool = newApplicationRolePool("workspace-member-rls", 1)) {
            JdbcClient app = JdbcClient.create(pool);
            TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));

            long withoutContext =
                    app.sql(countMembers).param(workspaceId).query(Long.class).single();
            Long asOwner = transactions.execute(status -> {
                app.sql("SELECT set_config('app.user_id', ?, true)")
                        .param(userId.toString())
                        .query(String.class)
                        .single();
                return app.sql(countMembers)
                        .param(workspaceId)
                        .query(Long.class)
                        .single();
            });
            Long asSomeoneElse = transactions.execute(status -> {
                app.sql("SELECT set_config('app.user_id', ?, true)")
                        .param(UUID.randomUUID().toString())
                        .query(String.class)
                        .single();
                return app.sql(countMembers)
                        .param(workspaceId)
                        .query(Long.class)
                        .single();
            });

            assertThat(withoutContext).isZero();
            assertThat(asOwner).isEqualTo(1);
            assertThat(asSomeoneElse).isZero();
        }
    }

    private ResultActions create(String accessToken, String name) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        return iam.postJson(WORKSPACES, body, accessToken);
    }

    private UUID createdId(String accessToken, String name) throws Exception {
        String body = create(accessToken, name)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Seeds a membership directly; the endpoints that add members arrive in later steps. */
    private static void addMember(UUID workspaceId, UUID userId, String role) {
        superuser()
                .sql("INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, ?, now())")
                .param(workspaceId)
                .param(userId)
                .param(role)
                .update();
    }

    /** Only for seeding and inspection: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
