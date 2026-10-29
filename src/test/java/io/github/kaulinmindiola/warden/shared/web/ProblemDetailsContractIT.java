package io.github.kaulinmindiola.warden.shared.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * Contract tests for the error format, through the real application context and its message
 * converters. MockMvc runs without servlet filters: these tests cover error rendering, not
 * security. The test controller exists only in this test's context.
 */
class ProblemDetailsContractIT extends IntegrationTest {

    private static final String BASE = ApiWebConfiguration.API_PREFIX + "/test";
    private static final String SENSITIVE = "Sensitive internal detail in com.example.SecretRepository";
    private static final String TRACE_ID = "[0-9a-f]{32}";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @ParameterizedTest
    @EnumSource(ProblemType.class)
    @Verifies({"REQ-INT-005", "REQ-COMP-001"})
    void everyCatalogueTypeIsRenderedAsProblemDetails(ProblemType type) throws Exception {
        expectProblem(mockMvc.perform(get(BASE + "/problems/" + type.slug())), type)
                .andExpect(jsonPath("$.detail").value(type.defaultDetail()))
                .andExpect(jsonPath("$.instance").value(BASE + "/problems/" + type.slug()));
    }

    @Test
    @Verifies("REQ-INT-005")
    void anInvalidBodyListsEachFieldError() throws Exception {
        expectProblem(
                        mockMvc.perform(post(BASE + "/validated")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\",\"code\":\"too-long\"}")),
                        ProblemType.VALIDATION_ERROR)
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "code")))
                .andExpect(jsonPath("$.errors[0].reason").isNotEmpty());
    }

    @Test
    @Verifies("REQ-INT-005")
    void anInvalidParameterIsReportedByName() throws Exception {
        expectProblem(mockMvc.perform(get(BASE + "/sized").param("size", "101")), ProblemType.VALIDATION_ERROR)
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void aParameterOfTheWrongTypeIsAValidationError() throws Exception {
        expectProblem(mockMvc.perform(get(BASE + "/sized").param("size", "abc")), ProblemType.VALIDATION_ERROR)
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void anUnreadableBodyIsMalformed() throws Exception {
        expectProblem(
                mockMvc.perform(post(BASE + "/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json")),
                ProblemType.MALFORMED_REQUEST);
    }

    @Test
    void anUnmappedPathIsNotFound() throws Exception {
        expectProblem(mockMvc.perform(get(BASE + "/does-not-exist")), ProblemType.NOT_FOUND);
    }

    @Test
    void anUnsupportedMethodIsMethodNotAllowedWithAllowHeader() throws Exception {
        expectProblem(mockMvc.perform(delete(BASE + "/problems/not-found")), ProblemType.METHOD_NOT_ALLOWED)
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    void anUnsupportedContentTypeIsReported() throws Exception {
        expectProblem(
                mockMvc.perform(post(BASE + "/validated")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name")),
                ProblemType.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void aRateLimitedProblemTellsWhenToRetryInWholeSecondsRoundedUp() throws Exception {
        expectProblem(mockMvc.perform(get(BASE + "/retry-after")), ProblemType.RATE_LIMITED)
                .andExpect(jsonPath("$.detail").value(ProblemType.RATE_LIMITED.defaultDetail()))
                .andExpect(header().string("Retry-After", "2"));
    }

    @Test
    void aProblemWithoutARetryTimeHasNoRetryAfterHeader() throws Exception {
        expectProblem(mockMvc.perform(get(BASE + "/problems/not-found")), ProblemType.NOT_FOUND)
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @Verifies("REQ-REL-009")
    void aDatabaseOutageIsServiceUnavailableWithRetryAfterAndNoInternals() throws Exception {
        expectProblem(mockMvc.perform(get(BASE + "/database-unavailable")), ProblemType.SERVICE_UNAVAILABLE)
                .andExpect(jsonPath("$.detail").value(ProblemType.SERVICE_UNAVAILABLE.defaultDetail()))
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().string(not(containsString("HikariPool"))))
                .andExpect(content().string(not(containsString("SQL"))));
    }

    @Test
    @Verifies("REQ-SEC-017")
    void anUnexpectedExceptionRevealsNoInternals() throws Exception {
        expectProblem(mockMvc.perform(get(BASE + "/boom")), ProblemType.INTERNAL_ERROR)
                .andExpect(jsonPath("$.detail").value(ProblemType.INTERNAL_ERROR.defaultDetail()))
                .andExpect(content().string(not(containsString("Sensitive"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("com.example"))))
                .andExpect(content().string(not(containsString("at io.github"))));
    }

    private static ResultActions expectProblem(ResultActions result, ProblemType type) throws Exception {
        return result.andExpect(status().is(type.status().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(type.uri().toString()))
                .andExpect(jsonPath("$.title").value(type.title()))
                .andExpect(jsonPath("$.status").value(type.status().value()))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").isNotEmpty())
                .andExpect(jsonPath("$.traceId", matchesPattern(TRACE_ID)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ContractEndpoints {

        @Bean
        ProblemContractController problemContractController() {
            return new ProblemContractController();
        }
    }

    @TestComponent
    @RestController
    @RequestMapping("/test")
    static class ProblemContractController {

        @GetMapping("/problems/{slug}")
        void problem(@PathVariable String slug) {
            throw new ProblemException(ProblemType.fromSlug(slug).orElseThrow());
        }

        @PostMapping(path = "/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
        void validated(@Valid @RequestBody ContractBody body) {}

        @GetMapping("/sized")
        int sized(@RequestParam @Max(100) int size) {
            return size;
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException(SENSITIVE);
        }

        @GetMapping("/retry-after")
        void retryAfter() {
            throw ProblemException.withRetryAfter(ProblemType.RATE_LIMITED, Duration.ofMillis(1500));
        }

        @GetMapping("/database-unavailable")
        void databaseUnavailable() {
            throw new CannotCreateTransactionException(
                    "Could not open JPA EntityManager for transaction",
                    new SQLTransientConnectionException(
                            "HikariPool-1 - Connection is not available, request timed out after 2000ms"));
        }
    }

    record ContractBody(
            @NotBlank String name, @Size(max = 5) String code) {}
}
