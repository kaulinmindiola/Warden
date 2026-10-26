package io.github.kaulinmindiola.warden.notes.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.ResponseAssertions.assertIdenticalResponses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * REQ-FUNC-025 and RISK-009: a user who is a member of W and X reaches W's routes, so the
 * authorization interceptor lets the request through; the note of X must still be invisible and
 * untouchable through W, because the effective tenant is the one in the path (SDD V07 L2, L3).
 */
class NoteCrossWorkspaceIT extends IntegrationTest {

    private static final String NOT_FOUND = "urn:warden:problem:not-found";

    @Autowired
    private WebApplicationContext context;

    @Test
    @Verifies({"REQ-FUNC-025", "REQ-SEC-012"})
    void aNoteOfXCannotBeReadEditedOrDeletedThroughW() throws Exception {
        IamTestClient iam = new IamTestClient(context);
        String token = iam.login(iam.registerVerifiedAccount());
        UUID w = createWorkspace(iam, token, "W");
        UUID x = createWorkspace(iam, token, "X");
        String noteOfX = createNote(iam, token, x, "Secret of X");
        String throughW = API + "/workspaces/" + w + "/notes/" + noteOfX;
        String unknownInW = API + "/workspaces/" + w + "/notes/" + UUID.randomUUID();

        MvcResult read = iam.get(throughW, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(NOT_FOUND))
                .andReturn();
        MvcResult edit = iam.mvc()
                .perform(MockMvcRequestBuilders.put(throughW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hijacked\",\"body\":\"Body\",\"version\":0}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        MvcResult delete = iam.delete(throughW, token).andReturn();
        MvcResult unknown = iam.get(unknownInW, token).andReturn();

        assertIdenticalResponses(unknown, read);
        assertIdenticalResponses(unknown, edit);
        assertIdenticalResponses(unknown, delete);

        String listOfW = iam.get(API + "/workspaces/" + w + "/notes", token)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listOfW).doesNotContain(noteOfX).doesNotContain("Secret of X");

        iam.get(API + "/workspaces/" + x + "/notes/" + noteOfX, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Secret of X"))
                .andExpect(jsonPath("$.version").value(0));
    }

    private static UUID createWorkspace(IamTestClient iam, String token, String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), token)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static String createNote(IamTestClient iam, String token, UUID workspaceId, String title) throws Exception {
        String body = iam.postJson(
                        API + "/workspaces/" + workspaceId + "/notes", Map.of("title", title, "body", "Body"), token)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
