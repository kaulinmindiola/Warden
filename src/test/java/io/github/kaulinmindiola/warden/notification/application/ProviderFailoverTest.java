package io.github.kaulinmindiola.warden.notification.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.kaulinmindiola.warden.notification.domain.EmailMessage;
import io.github.kaulinmindiola.warden.notification.domain.EmailProvider;
import io.github.kaulinmindiola.warden.notification.domain.EmailProviders;
import io.github.kaulinmindiola.warden.notification.domain.ProviderResult;
import io.github.kaulinmindiola.warden.notification.infrastructure.CircuitBreakerProperties;
import io.github.kaulinmindiola.warden.notification.infrastructure.HttpEmailProvider;
import io.github.kaulinmindiola.warden.notification.infrastructure.ProviderCircuitBreakers;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-attempt failover and per-provider circuit breakers (REQ-FUNC-055, REQ-REL-004). The breakers
 * use the SRS thresholds; only the time they stay open is shortened, so the probe phase is reached
 * without waiting 30 s. That the configured wait is 30 s is checked separately.
 */
class ProviderFailoverTest {

    private static final Duration SHORT_WAIT = Duration.ofMillis(200);

    private final FakeProvider a = new FakeProvider("A");
    private final FakeProvider b = new FakeProvider("B");
    private final ProviderCircuitBreakers breakers =
            new ProviderCircuitBreakers(new CircuitBreakerProperties(20, 10, 50, SHORT_WAIT, 3));
    private final ProviderFailover failover = new ProviderFailover(new EmailProviders(a, b), breakers);

    @Test
    @Verifies("REQ-FUNC-055")
    void aHealthyPrimaryTakesTheMessageAndTheSecondaryIsNotCalled() {
        assertThat(failover.send(message())).isEqualTo(new SendOutcome(ProviderResult.ACCEPTED, "A"));
        assertThat(a.calls()).isEqualTo(1);
        assertThat(b.calls()).isZero();
    }

    @Test
    @Verifies("REQ-FUNC-055")
    void aTransientFailureOfThePrimaryFailsOverToTheSecondaryInTheSameAttempt() {
        a.answer(ProviderResult.TRANSIENT_FAILURE);

        assertThat(failover.send(message())).isEqualTo(new SendOutcome(ProviderResult.ACCEPTED, "B"));
        assertThat(a.calls()).isEqualTo(1);
        assertThat(b.calls()).isEqualTo(1);
    }

    @Test
    @Verifies("REQ-FUNC-056")
    void aPermanentRejectionEndsTheAttemptWithoutFailover() {
        a.answer(ProviderResult.PERMANENT_REJECTION);

        assertThat(failover.send(message())).isEqualTo(new SendOutcome(ProviderResult.PERMANENT_REJECTION, "A"));
        assertThat(b.calls()).isZero();
    }

    @Test
    void whenBothFailTransientlyTheLastProviderCalledIsReported() {
        a.answer(ProviderResult.TRANSIENT_FAILURE);
        b.answer(ProviderResult.TRANSIENT_FAILURE);

        assertThat(failover.send(message())).isEqualTo(new SendOutcome(ProviderResult.TRANSIENT_FAILURE, "B"));
    }

    @Test
    @Verifies({"REQ-REL-004", "REQ-FUNC-055"})
    void thePrimaryBreakerOpensAfterTenFailuresAndThePrimaryReceivesNoMoreCalls() {
        a.answer(ProviderResult.TRANSIENT_FAILURE);
        for (int attempt = 0; attempt < 9; attempt++) {
            failover.send(message());
        }
        assertThat(breakers.state("A")).isEqualTo(CircuitBreaker.State.CLOSED);

        failover.send(message());

        assertThat(breakers.state("A")).isEqualTo(CircuitBreaker.State.OPEN);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(failover.send(message())).isEqualTo(new SendOutcome(ProviderResult.ACCEPTED, "B"));
        }
        assertThat(a.calls()).isEqualTo(10);
        assertThat(b.calls()).isEqualTo(15);
    }

    @Test
    @Verifies("REQ-REL-004")
    void halfOfTheCallsFailingOpensTheBreakerAndLessDoesNot() {
        ProviderCircuitBreakers atThreshold = new ProviderCircuitBreakers(CircuitBreakerProperties.defaults());
        ProviderCircuitBreakers belowThreshold = new ProviderCircuitBreakers(CircuitBreakerProperties.defaults());
        for (int call = 0; call < 10; call++) {
            atThreshold.tryAcquire("A");
            atThreshold.record("A", call < 5, 1);
            belowThreshold.tryAcquire("A");
            belowThreshold.record("A", call < 4, 1);
        }

        assertThat(atThreshold.state("A")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(belowThreshold.state("A")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @Verifies("REQ-REL-004")
    void permanentRejectionsDoNotCountAsFailures() {
        a.answer(ProviderResult.PERMANENT_REJECTION);
        for (int attempt = 0; attempt < 20; attempt++) {
            failover.send(message());
        }

        assertThat(breakers.state("A")).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(a.calls()).isEqualTo(20);
    }

    @Test
    @Verifies("REQ-REL-004")
    void afterTheWaitThreeSuccessfulProbeCallsCloseTheBreaker() throws InterruptedException {
        openPrimaryBreaker();
        Thread.sleep(SHORT_WAIT.toMillis() + 100);
        a.answer(ProviderResult.ACCEPTED);
        int before = a.calls();

        for (int probe = 0; probe < 3; probe++) {
            assertThat(failover.send(message())).isEqualTo(new SendOutcome(ProviderResult.ACCEPTED, "A"));
        }

        assertThat(a.calls() - before).isEqualTo(3);
        assertThat(breakers.state("A")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @Verifies("REQ-REL-004")
    void afterTheWaitExactlyThreeProbeCallsAreAllowedAndFailingProbesOpenTheBreakerAgain() throws InterruptedException {
        openPrimaryBreaker();
        Thread.sleep(SHORT_WAIT.toMillis() + 100);
        int before = a.calls();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(failover.send(message()).provider()).isEqualTo("B");
        }

        assertThat(a.calls() - before).isEqualTo(3);
        assertThat(breakers.state("A")).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void whenBothBreakersAreOpenNoProviderIsCalled() {
        a.answer(ProviderResult.TRANSIENT_FAILURE);
        b.answer(ProviderResult.TRANSIENT_FAILURE);
        for (int attempt = 0; attempt < 10; attempt++) {
            failover.send(message());
        }
        int callsA = a.calls();
        int callsB = b.calls();

        assertThat(failover.send(message()))
                .isEqualTo(new SendOutcome(ProviderResult.TRANSIENT_FAILURE, SendOutcome.NONE));
        assertThat(a.calls()).isEqualTo(callsA);
        assertThat(b.calls()).isEqualTo(callsB);
    }

    @Test
    @Verifies("REQ-REL-004")
    void theDefaultBreakerUsesTheSrsValues() {
        CircuitBreakerConfig config = new ProviderCircuitBreakers(CircuitBreakerProperties.defaults()).config();

        assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(20);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
    }

    @Test
    @Verifies("REQ-FUNC-055")
    void overHttpAPrimaryAnswering500FailsOverToTheSecondaryOnTheFirstAttempt() {
        WireMockServer primary = new WireMockServer(options().dynamicPort());
        WireMockServer secondary =
                new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("ops/providers/b"));
        primary.start();
        secondary.start();
        primary.stubFor(
                post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withStatus(500)));
        JsonMapper json = JsonMapper.builder().build();
        try (HttpEmailProvider httpA = http("A", primary, json);
                HttpEmailProvider httpB = http("B", secondary, json)) {
            ProviderFailover overHttp = new ProviderFailover(
                    new EmailProviders(httpA, httpB), new ProviderCircuitBreakers(CircuitBreakerProperties.defaults()));

            assertThat(overHttp.send(message())).isEqualTo(new SendOutcome(ProviderResult.ACCEPTED, "B"));
            primary.verify(1, postRequestedFor(urlPathEqualTo("/v1/messages")));
            secondary.verify(1, postRequestedFor(urlPathEqualTo("/v1/messages")));
        } finally {
            primary.stop();
            secondary.stop();
        }
    }

    private void openPrimaryBreaker() {
        a.answer(ProviderResult.TRANSIENT_FAILURE);
        for (int attempt = 0; attempt < 10; attempt++) {
            failover.send(message());
        }
        assertThat(breakers.state("A")).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private static HttpEmailProvider http(String name, WireMockServer server, JsonMapper json) {
        return new HttpEmailProvider(
                name, URI.create(server.baseUrl()), Duration.ofSeconds(2), Duration.ofSeconds(5), json);
    }

    private static EmailMessage message() {
        return new EmailMessage(UUID.randomUUID().toString(), "ana@mail.test", "Subject", "Body");
    }

    /** A provider that answers what it is told and counts its calls. */
    private static final class FakeProvider implements EmailProvider {

        private final String name;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ProviderResult> answer = new AtomicReference<>(ProviderResult.ACCEPTED);

        FakeProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ProviderResult send(EmailMessage message) {
            calls.incrementAndGet();
            return answer.get();
        }

        void answer(ProviderResult result) {
            answer.set(result);
        }

        int calls() {
            return calls.get();
        }
    }
}
