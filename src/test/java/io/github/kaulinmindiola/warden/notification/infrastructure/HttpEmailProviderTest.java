package io.github.kaulinmindiola.warden.notification.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.kaulinmindiola.warden.notification.domain.EmailMessage;
import io.github.kaulinmindiola.warden.notification.domain.ProviderResult;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP provider adapter against WireMock loaded with the mappings of {@code ops/providers},
 * the same ones the Compose environment serves (REQ-INT-008, REQ-REL-005). Answers the mappings
 * do not cover are stubbed here with a higher priority.
 */
class HttpEmailProviderTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WireMockServer providerA;
    private HttpEmailProvider provider;

    @BeforeEach
    void startProviderA() {
        providerA = new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("ops/providers/a"));
        providerA.start();
        provider = providerFor(providerA, "A");
    }

    @AfterEach
    void stopProviderA() {
        provider.close();
        providerA.stop();
    }

    @Test
    @Verifies("REQ-INT-008")
    void a202IsAcceptedAndTheRequestFollowsTheWireContract() {
        String deliveryId = UUID.randomUUID().toString();

        ProviderResult result = provider.send(new EmailMessage(deliveryId, "ana@mail.test", "Subject", "Body #token"));

        assertThat(result).isEqualTo(ProviderResult.ACCEPTED);
        providerA.verify(
                1,
                postRequestedFor(urlPathEqualTo("/v1/messages"))
                        .withHeader("Content-Type", containing("application/json"))
                        .withRequestBody(
                                equalToJson(
                                        "{\"idempotencyKey\": \"" + deliveryId
                                                + "\", \"to\": \"ana@mail.test\", \"subject\": \"Subject\", \"text\": \"Body #token\"}")));
    }

    @Test
    @Verifies("REQ-INT-008")
    void anInvalidRecipientIsAPermanentRejection() {
        assertThat(send("someone@invalid.test")).isEqualTo(ProviderResult.PERMANENT_REJECTION);
    }

    @Test
    @Verifies("REQ-INT-008")
    void a503IsATransientFailure() {
        assertThat(send("someone@unavailable.test")).isEqualTo(ProviderResult.TRANSIENT_FAILURE);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {200, 400, 404, 429, 500, 502})
    @Verifies("REQ-INT-008")
    void everyOtherStatusIsATransientFailure(int status) {
        stubStatusFor("status@mail.test", status, "{}");

        assertThat(send("status@mail.test")).isEqualTo(ProviderResult.TRANSIENT_FAILURE);
    }

    @ParameterizedTest(name = "422 with {0}")
    @ValueSource(strings = {"{\"code\": \"quota_exceeded\"}", "{}", "not json", ""})
    @Verifies("REQ-INT-008")
    void a422WithoutTheInvalidRecipientCodeIsATransientFailure(String body) {
        stubStatusFor("other@mail.test", 422, body);

        assertThat(send("other@mail.test")).isEqualTo(ProviderResult.TRANSIENT_FAILURE);
    }

    @Test
    @Verifies("REQ-INT-008")
    void anUnreachableProviderIsATransientFailure() {
        WireMockServer stopped = new WireMockServer(options().dynamicPort());
        stopped.start();
        int port = stopped.port();
        stopped.stop();

        try (HttpEmailProvider unreachable = new HttpEmailProvider(
                "A", URI.create("http://localhost:" + port), CONNECT_TIMEOUT, REQUEST_TIMEOUT, JSON)) {
            assertThat(unreachable.send(message("ana@mail.test"))).isEqualTo(ProviderResult.TRANSIENT_FAILURE);
        }
    }

    @Test
    @Verifies("REQ-REL-005")
    void aProviderAnsweringAfterSixSecondsFailsTransientlyAtFiveSeconds() {
        long start = System.nanoTime();

        ProviderResult result = send("someone@slow.test");

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(result).isEqualTo(ProviderResult.TRANSIENT_FAILURE);
        assertThat(elapsed).isBetween(Duration.ofMillis(4500), Duration.ofMillis(5500));
    }

    @Test
    @Verifies("REQ-INT-008")
    void providerBServesTheSameContract() {
        WireMockServer providerB =
                new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("ops/providers/b"));
        providerB.start();
        try (HttpEmailProvider b = providerFor(providerB, "B")) {
            assertThat(b.name()).isEqualTo("B");
            assertThat(b.send(message("ana@mail.test"))).isEqualTo(ProviderResult.ACCEPTED);
            assertThat(b.send(message("someone@invalid.test"))).isEqualTo(ProviderResult.PERMANENT_REJECTION);
            assertThat(b.send(message("someone@unavailable.test"))).isEqualTo(ProviderResult.TRANSIENT_FAILURE);
        } finally {
            providerB.stop();
        }
    }

    @Test
    void aTrailingSlashInTheBaseUrlIsIgnored() {
        try (HttpEmailProvider slashed = new HttpEmailProvider(
                "A", URI.create(providerA.baseUrl() + "/"), CONNECT_TIMEOUT, REQUEST_TIMEOUT, JSON)) {
            assertThat(slashed.send(message("ana@mail.test"))).isEqualTo(ProviderResult.ACCEPTED);
        }
    }

    @Test
    void theMessageNeverPrintsItsRecipientOrContent() {
        assertThat(message("ana@mail.test").toString())
                .doesNotContain("ana@mail.test")
                .doesNotContain("Body");
    }

    private ProviderResult send(String to) {
        return provider.send(message(to));
    }

    private static EmailMessage message(String to) {
        return new EmailMessage(UUID.randomUUID().toString(), to, "Subject", "Body");
    }

    private void stubStatusFor(String to, int status, String body) {
        providerA.stubFor(post(urlPathEqualTo("/v1/messages"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.to", equalTo(to)))
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private static HttpEmailProvider providerFor(WireMockServer server, String name) {
        return new HttpEmailProvider(name, URI.create(server.baseUrl()), CONNECT_TIMEOUT, REQUEST_TIMEOUT, JSON);
    }
}
