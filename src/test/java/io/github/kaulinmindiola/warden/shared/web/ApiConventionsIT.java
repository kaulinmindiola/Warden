package io.github.kaulinmindiola.warden.shared.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * API conventions through the real application context: version prefix, identifiers and
 * timestamps, pagination and the request body limit. MockMvc includes the body size filter but
 * not the security chain.
 */
class ApiConventionsIT extends IntegrationTest {

    private static final String BASE = ApiWebConfiguration.API_PREFIX + "/conventions";
    private static final String UUID_V7 = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05.123456Z");
    private static final int TOTAL_ITEMS = 45;
    private static final int LIMIT = (int) RequestSizeLimitFilter.MAX_BODY_BYTES;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RequestSizeLimitFilter sizeLimitFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sizeLimitFilter)
                .build();
    }

    @Test
    @Verifies("REQ-INT-001")
    void endpointsAreServedOnlyUnderTheVersionPrefix() throws Exception {
        mockMvc.perform(get(BASE + "/sample")).andExpect(status().isOk());
        mockMvc.perform(get("/conventions/sample"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ProblemType.NOT_FOUND.uri().toString()));
    }

    @Test
    @Verifies("REQ-INT-007")
    void identifiersAreUuidsAndTimestampsAreUtc() throws Exception {
        mockMvc.perform(get(BASE + "/sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", matchesPattern(UUID_V7)))
                .andExpect(jsonPath("$.createdAt").value("2026-01-02T03:04:05.123456Z"));
    }

    @Test
    @Verifies("REQ-INT-006")
    void listsDefaultToTheFirstPageOfTwentyItems() throws Exception {
        mockMvc.perform(get(BASE + "/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(TOTAL_ITEMS))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    @Verifies("REQ-INT-006")
    void theMetadataMatchesTheItemsOfTheLastPage() throws Exception {
        mockMvc.perform(get(BASE + "/items").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0]").value("item-40"))
                .andExpect(jsonPath("$.page").value(2));
    }

    @Test
    @Verifies("REQ-INT-006")
    void aPageSizeAboveOneHundredIsRejected() throws Exception {
        mockMvc.perform(get(BASE + "/items").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value(ProblemType.VALIDATION_ERROR.uri().toString()))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void aNegativePageIsRejected() throws Exception {
        mockMvc.perform(get(BASE + "/items").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    @Verifies("REQ-SEC-017")
    void aNonNumericPageSizeIsRejectedWithoutFrameworkDetails() throws Exception {
        mockMvc.perform(get(BASE + "/items").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].reason").value("has an invalid value"))
                .andExpect(content().string(not(containsString("java."))));
    }

    @Test
    @Verifies("REQ-SEC-018")
    void aBodyOfExactlySixtyFourKibibytesIsAccepted() throws Exception {
        mockMvc.perform(post(BASE + "/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBodyOfSize(LIMIT)))
                .andExpect(status().isOk());
    }

    @Test
    @Verifies("REQ-SEC-018")
    void aBodyOneByteOverTheLimitIsRejectedWith413() throws Exception {
        mockMvc.perform(post(BASE + "/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBodyOfSize(LIMIT + 1)))
                .andExpect(status().is(413))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value(ProblemType.PAYLOAD_TOO_LARGE.uri().toString()))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{32}")));
    }

    /** A JSON document {"text":"aaa..."} of exactly the given number of bytes. */
    private static String jsonBodyOfSize(int bytes) {
        String prefix = "{\"text\":\"";
        String suffix = "\"}";
        return prefix + "a".repeat(bytes - prefix.length() - suffix.length()) + suffix;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConventionEndpoints {

        @Bean
        ConventionController conventionController() {
            return new ConventionController();
        }
    }

    @TestComponent
    @RestController
    @RequestMapping("/conventions")
    static class ConventionController {

        private final UuidV7Generator ids = new UuidV7Generator(Clock.fixed(CREATED_AT, ZoneOffset.UTC));

        @GetMapping("/sample")
        Sample sample() {
            return new Sample(ids.next(), CREATED_AT);
        }

        @GetMapping("/items")
        PageResponse<String> items(@Valid PageQuery query) {
            List<String> all =
                    IntStream.range(0, TOTAL_ITEMS).mapToObj(i -> "item-" + i).toList();
            int from = Math.min(query.page() * query.size(), all.size());
            int to = Math.min(from + query.size(), all.size());
            return PageResponse.from(new PageImpl<>(all.subList(from, to), query.toPageable(), all.size()));
        }

        @PostMapping(path = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
        int echo(@RequestBody EchoBody body) {
            return body.text().length();
        }
    }

    record Sample(UUID id, Instant createdAt) {}

    record EchoBody(String text) {}
}
