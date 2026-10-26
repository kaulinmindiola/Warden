package io.github.kaulinmindiola.warden.workspace.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * REQ-FUNC-039 under real concurrency (SDD V14-A4): the two OWNERs of a workspace demote
 * themselves at the same time, 50 times. Without the workspace lock both requests would count two
 * OWNERs and succeed, leaving none; with it, exactly one succeeds, the other gets 409 last-owner,
 * and exactly one OWNER remains.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ConcurrentLastOwnerIT extends IntegrationTest {

    private static final int REPETITIONS = 50;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    @Verifies({"REQ-FUNC-039", "BR-001"})
    void twoOwnersDemotingThemselvesAtOnceNeverLeaveTheWorkspaceWithoutOwner() throws Exception {
        IamTestClient iam = new IamTestClient(context);
        String firstEmail = iam.registerVerifiedAccount();
        String secondEmail = iam.registerNewAccount();
        Owner first = new Owner(iam.userIdOf(firstEmail), iam.login(firstEmail));
        Owner second = new Owner(iam.userIdOf(secondEmail), iam.login(secondEmail));
        String base = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class) + API
                + "/workspaces/";

        try (HttpClient http = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
                ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                UUID workspaceId = createWorkspace(iam, first.token(), "Race " + repetition);
                addOwner(workspaceId, second.userId());

                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> responses = new ArrayList<>();
                for (Owner owner : List.of(first, second)) {
                    HttpRequest request = HttpRequest.newBuilder(
                                    URI.create(base + workspaceId + "/members/" + owner.userId() + "/role"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + owner.token())
                            .PUT(HttpRequest.BodyPublishers.ofString("{\"role\":\"ADMIN\"}"))
                            .build();
                    responses.add(callers.submit(() -> {
                        start.await();
                        return http.send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode();
                    }));
                }
                start.countDown();

                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> response : responses) {
                    statuses.add(response.get(30, TimeUnit.SECONDS));
                }
                assertThat(statuses).as("statuses in repetition %d", repetition).containsExactlyInAnyOrder(200, 409);
                assertThat(ownerCount(workspaceId))
                        .as("OWNERs left in repetition %d", repetition)
                        .isEqualTo(1);
            }
        }
        System.out.println("REQ-FUNC-039: " + REPETITIONS
                + " repetitions of 2 OWNERs demoting themselves at once, exactly one 200 and one 409 each,"
                + " one OWNER left");
    }

    private record Owner(UUID userId, String token) {}

    private static UUID createWorkspace(IamTestClient iam, String token, String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), token)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static void addOwner(UUID workspaceId, UUID userId) {
        superuser()
                .sql(
                        "INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, 'OWNER', now())")
                .param(workspaceId)
                .param(userId)
                .update();
    }

    private static long ownerCount(UUID workspaceId) {
        return superuser()
                .sql("SELECT count(*) FROM workspace_member WHERE workspace_id = ? AND role = 'OWNER'")
                .param(workspaceId)
                .query(Long.class)
                .single();
    }

    /** Only for seeding and inspection: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
