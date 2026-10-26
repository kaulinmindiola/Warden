package io.github.kaulinmindiola.warden.notes.web;

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
 * REQ-FUNC-045 under real concurrency: two HTTP edits of the same version of a note, released
 * together, repeated 50 times. In every repetition exactly one succeeds, the other gets 409, and
 * the note ends at version 1: no update is silently lost.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ConcurrentNoteEditIT extends IntegrationTest {

    private static final int REPETITIONS = 50;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    @Verifies("REQ-FUNC-045")
    void exactlyOneOfTwoConcurrentEditsOfTheSameVersionWins() throws Exception {
        IamTestClient iam = new IamTestClient(context);
        String email = iam.registerVerifiedAccount();
        String token = iam.login(email);
        UUID authorId = iam.userIdOf(email);
        String workspaceBody = iam.postJson(API + "/workspaces", Map.of("name", "Race"), token)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID workspaceId = UUID.fromString(JsonPath.read(workspaceBody, "$.id"));
        String base = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class) + API
                + "/workspaces/" + workspaceId + "/notes/";

        try (HttpClient http = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
                ExecutorService editors = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                UUID noteId = seedNote(workspaceId, authorId);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> responses = new ArrayList<>();
                for (int editor = 1; editor <= 2; editor++) {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(base + noteId))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .PUT(HttpRequest.BodyPublishers.ofString(
                                    "{\"title\":\"Editor " + editor + "\",\"body\":\"Body\",\"version\":0}"))
                            .build();
                    responses.add(editors.submit(() -> {
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
                assertThat(versionOf(noteId))
                        .as("final version in repetition %d", repetition)
                        .isEqualTo(1);
            }
        }
        System.out.println("REQ-FUNC-045: " + REPETITIONS
                + " repetitions of 2 concurrent edits of the same version, exactly one 200 and one 409 each");
    }

    private static UUID seedNote(UUID workspaceId, UUID authorId) {
        UUID noteId = UUID.randomUUID();
        superuser().sql("""
                        INSERT INTO note (id, workspace_id, title, body, author_id, version, created_at, updated_at)
                        VALUES (?, ?, 'Contested', 'Body', ?, 0, now(), now())
                        """).param(noteId).param(workspaceId).param(authorId).update();
        return noteId;
    }

    private static long versionOf(UUID noteId) {
        return superuser()
                .sql("SELECT version FROM note WHERE id = ?")
                .param(noteId)
                .query(Long.class)
                .single();
    }

    /** Only for seeding and inspection: the superuser bypasses Row Level Security. */
    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
