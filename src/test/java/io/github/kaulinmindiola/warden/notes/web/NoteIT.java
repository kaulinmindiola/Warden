package io.github.kaulinmindiola.warden.notes.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** UC-20 and UC-21 through the full filter chain, against PostgreSQL with Row Level Security. */
class NoteIT extends IntegrationTest {

    private static final String VALIDATION_ERROR = "urn:warden:problem:validation-error";
    private static final String NOT_FOUND = "urn:warden:problem:not-found";
    private static final String INSUFFICIENT_ROLE = "urn:warden:problem:insufficient-role";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    private IamTestClient iam;
    private UUID workspaceId;
    private String notes;

    @BeforeEach
    void createWorkspace() throws Exception {
        iam = new IamTestClient(context);
        String ownerToken = iam.login(iam.registerVerifiedAccount());
        String body = iam.postJson(API + "/workspaces", Map.of("name", "Notes"), ownerToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        workspaceId = UUID.fromString(JsonPath.read(body, "$.id"));
        notes = API + "/workspaces/" + workspaceId + "/notes";
    }

    @Test
    @Verifies("REQ-FUNC-044")
    void aMemberCreatesANoteThatRecordsItsAuthor() throws Exception {
        Member member = member("MEMBER");

        MvcResult result = create(member.token(), "  Plan  ", "Body")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Plan"))
                .andExpect(jsonPath("$.body").value("Body"))
                .andExpect(jsonPath("$.authorId").value(member.userId().toString()))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        assertThat(result.getResponse().getHeader("Location")).endsWith(notes + "/" + id);
        assertThat((String) JsonPath.read(body, "$.createdAt")).isEqualTo(JsonPath.read(body, "$.updatedAt"));
    }

    @Test
    @Verifies({"REQ-FUNC-043", "REQ-FUNC-044", "REQ-FUNC-045", "REQ-FUNC-046"})
    void aViewerReadsNotesButCannotWriteThem() throws Exception {
        String noteId = createdId(member("MEMBER").token(), "Shared", "Visible body");
        String viewer = member("VIEWER").token();

        get(notes, viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        get(notes + "/" + noteId, viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Visible body"));
        create(viewer, "Nope", "Body")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(INSUFFICIENT_ROLE));
        put(noteId, Map.of("title", "Nope", "body", "Body", "version", 0), viewer)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(INSUFFICIENT_ROLE));
        delete(noteId, viewer)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(INSUFFICIENT_ROLE));

        get(notes + "/" + noteId, viewer)
                .andExpect(jsonPath("$.title").value("Shared"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @Verifies("REQ-FUNC-044")
    void titleAndBodyLengthsAreEnforced() throws Exception {
        String token = member("MEMBER").token();

        create(token, "a".repeat(200), "b".repeat(10_000)).andExpect(status().isCreated());
        create(token, "a".repeat(201), "Body")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.errors[0].field").value("title"))
                .andExpect(jsonPath("$.errors[0].reason").value("must be between 1 and 200 characters"));
        create(token, "Title", "b".repeat(10_001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"))
                .andExpect(jsonPath("$.errors[0].reason").value("must be at most 10000 characters"));
        create(token, "   ", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[1].reason").value("is required"));
    }

    @Test
    @Verifies("REQ-FUNC-045")
    void anEditStatesTheCurrentVersionAndAStaleOneGets409() throws Exception {
        String token = member("MEMBER").token();
        String noteId = createdId(token, "Draft", "First");
        clock.advance(Duration.ofMinutes(1));

        String edited = put(noteId, Map.of("title", "Final", "body", "Second", "version", 0), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Final"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(Instant.parse(JsonPath.read(edited, "$.updatedAt")))
                .isAfter(Instant.parse(JsonPath.read(edited, "$.createdAt")));

        put(noteId, Map.of("title", "Lost", "body", "Update", "version", 0), token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:warden:problem:version-conflict"));
        Map<String, Object> withoutVersion = new HashMap<>();
        withoutVersion.put("title", "No");
        withoutVersion.put("body", "Version");
        put(noteId, withoutVersion, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));

        get(notes + "/" + noteId, token)
                .andExpect(jsonPath("$.title").value("Final"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @Verifies("REQ-FUNC-046")
    void aDeletedNoteIsGoneFromTheDetailAndTheList() throws Exception {
        String token = member("MEMBER").token();
        String noteId = createdId(token, "Temporary", "Body");

        delete(noteId, token).andExpect(status().isNoContent());

        get(notes + "/" + noteId, token).andExpect(status().isNotFound());
        get(notes, token).andExpect(jsonPath("$.totalElements").value(0));
        delete(noteId, token).andExpect(status().isNotFound());
    }

    @Test
    @Verifies("REQ-FUNC-043")
    void anUnknownNoteIs404ForEveryOperation() throws Exception {
        String token = member("MEMBER").token();
        String unknown = UUID.randomUUID().toString();

        get(notes + "/" + unknown, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND));
        put(unknown, Map.of("title", "T", "body", "B", "version", 0), token).andExpect(status().isNotFound());
        delete(unknown, token).andExpect(status().isNotFound());
    }

    @Test
    @Verifies("REQ-FUNC-043")
    void theListShowsNewestFirstWithoutBodies() throws Exception {
        String token = member("MEMBER").token();
        createdId(token, "Older", "Body");
        clock.advance(Duration.ofSeconds(1));
        createdId(token, "Newer", "Body");

        get(notes, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Newer"))
                .andExpect(jsonPath("$.items[1].title").value("Older"))
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.items[0].version").value(0));
    }

    private record Member(UUID userId, String token) {}

    /** A new user with the given role in the workspace, seeded as the superuser. */
    private Member member(String role) throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        JdbcClient.create(superuserDataSource())
                .sql("INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, ?, now())")
                .param(workspaceId)
                .param(userId)
                .param(role)
                .update();
        return new Member(userId, iam.login(email));
    }

    private ResultActions create(String token, String title, String body) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("title", title);
        request.put("body", body);
        return iam.postJson(notes, request, token);
    }

    private String createdId(String token, String title, String body) throws Exception {
        String response = create(token, title, body)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private ResultActions get(String path, String token) throws Exception {
        return iam.get(path, token);
    }

    private ResultActions put(String noteId, Map<String, ?> body, String token) throws Exception {
        return iam.mvc()
                .perform(MockMvcRequestBuilders.put(notes + "/" + noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new HashMap<>(body)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions delete(String noteId, String token) throws Exception {
        return iam.delete(notes + "/" + noteId, token);
    }
}
