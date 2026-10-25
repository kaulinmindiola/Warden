package io.github.kaulinmindiola.warden.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Assertions for anti-enumeration tests (BR-007). */
public final class ResponseAssertions {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ResponseAssertions() {}

    /**
     * Asserts that two responses are indistinguishable to a client: same status, same content
     * type and same body, ignoring only {@code traceId} and {@code instance}, which identify the
     * request rather than its outcome.
     */
    public static void assertIdenticalResponses(MvcResult first, MvcResult second) throws Exception {
        assertThat(second.getResponse().getStatus())
                .as("status")
                .isEqualTo(first.getResponse().getStatus());
        assertThat(second.getResponse().getContentType())
                .as("content type")
                .isEqualTo(first.getResponse().getContentType());
        assertThat(comparableBody(second)).as("body").isEqualTo(comparableBody(first));
    }

    private static String comparableBody(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (body.isBlank()) {
            return "";
        }
        JsonNode tree = JSON.readTree(body);
        if (tree instanceof ObjectNode object) {
            object.remove("traceId");
            object.remove("instance");
        }
        return tree.toString();
    }
}
