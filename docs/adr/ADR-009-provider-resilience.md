# ADR-009: Provider resilience: failover, circuit breakers, timeouts and backoff

- Status: accepted
- Date: 2026-11-01
- Deciders: Kaulin Mindiola
- Related: REQ-INT-008, REQ-FUNC-055, REQ-FUNC-056, REQ-FUNC-057, REQ-REL-003, REQ-REL-004,
  REQ-REL-005, REQ-REL-006, G-03, DEC-019, DEC-041, DEC-042, DEC-043, RISK-005; SDD V09, V13,
  V14-A5, V17, V21; ADR-008, ADR-010, ADR-015

## Context and Problem Statement

Every delivery leaves Warden through one of two e-mail providers, simulated in release 1.0. A
provider can answer slowly, fail for a while or reject an address for good. The SRS asks that a
failing primary does not delay deliveries (failover in the same attempt, REQ-FUNC-055), that a
provider failing repeatedly stops being called for a while (REQ-REL-004), that no call waits more
than 5 s (REQ-REL-005), that retries are spaced out (REQ-REL-006), and that none of this affects
the business endpoints (REQ-REL-003). The library choice also depends on Spring Boot 4.1 support
(RISK-005).

How does one delivery attempt use the two providers, and how are failures contained?

## Decision Drivers

- A transient failure of the primary must not cost a whole retry interval.
- A provider that keeps failing must stop receiving calls, and be tried again on its own.
- Bounded time per call, so that a hung provider cannot hold a worker.
- Results that the delivery state machine can act on: accepted, rejected for good, or worth
  another try.
- Deterministic tests with simulated providers, and no dependency on a Boot starter that may lag
  behind Boot 4.1.

## Considered Options

1. In-attempt failover with a Resilience4j core circuit breaker per provider, the JDK HttpClient
   for time limits, and backoff stored in the delivery (`next_attempt_at`).
2. The same through the Resilience4j Spring Boot starter and annotations (`@CircuitBreaker`,
   `@Retry`, `@TimeLimiter`).
3. Failover across attempts: the next retry goes to the other provider.
4. No circuit breaker: every attempt calls both providers.

## Decision Outcome

Chosen option: **1**.

- **Result classification (step 4.7).** `HttpEmailProvider` posts `{idempotencyKey, to, subject,
  text}` to `/v1/messages`: 202 is `ACCEPTED`, 422 with `invalid_recipient` is
  `PERMANENT_REJECTION`, everything else, timeouts and connection errors included, is
  `TRANSIENT_FAILURE`. The adapter never throws. The idempotency key is the delivery id, so a
  provider can recognize a resend.
- **Time limits.** The JDK `HttpClient` gives 2 s to connect and 5 s for the answer. Resilience4j's
  `TimeLimiter` was not needed and its dependency was removed.
- **Failover (step 4.8).** `ProviderFailover` calls the primary unless its breaker is open, and the
  secondary if the primary failed transiently or could not be called. A permanent rejection ends
  the attempt: another provider would reject the same address. The outcome names the provider
  called last, or `NONE` when both breakers were open.
- **Circuit breakers.** One per provider and instance, count-based: open when at least 50 % of the
  last 20 calls, with a minimum of 10, were transient failures; 30 s open; then 3 probe calls decide.
  Permanent rejections count as successes, since the provider is working. The values are
  configurable (`warden.notification.circuit-breaker.*`).
- **Backoff (step 4.11).** A failed attempt stores `next_attempt_at = now + min(60 s, 1 s · 2^(n−1))
  · uniform(0.5, 1.5)`; the 5th failure moves the delivery to `DEAD`. Spacing lives in the
  database, so it survives restarts and works with several instances; no in-memory retry.
- **Simulators.** Two WireMock instances serve the mappings of `ops/providers` to the tests and to
  Compose: the recipient's domain selects acceptance, permanent rejection, 503 or a 6 s delay.

### Consequences

- Good: a failing primary costs one extra call, not a retry interval.
- Good: a provider down for long receives at most 10 calls per window, then 3 probes every 30 s.
- Good: no Boot starter: the library is used as plain objects, independent of auto-configuration.
- Good: every outcome is a value, which keeps the delivery worker free of exception handling.
- Bad: breakers are per instance, so with N instances a failing provider receives up to N times
  the calls before every breaker opens. Accepted: the volumes are small and the probes cheap.
- Bad: delivery is at-least-once towards the provider: a timeout after the provider accepted leads
  to a resend. The idempotency key lets a real provider drop it; the simulators do not.
- Bad: the 30 s open wait is not covered by an injectable clock; the tests shorten the wait and
  check that the configured default is 30 s.

### Confirmation

- `HttpEmailProviderTest`: each answer of the contract gets its classification, a 6 s answer fails
  at 5 s ± 0.5 s, and provider B serves the same mappings (REQ-INT-008, REQ-REL-005).
- `ProviderFailoverTest`: a primary answering 500 over HTTP fails over to the secondary in the
  first attempt (REQ-FUNC-055); the breaker opens after 10 failures and the primary receives no
  further calls; 50 % opens it and 40 % does not; permanent rejections do not count; after the
  wait exactly 3 probe calls are allowed, closing the breaker when they succeed and reopening it
  when they fail (REQ-REL-004); the default configuration holds the SRS values.
- Re-confirmed in the delivery worker block, steps 4.9 to 4.13; a failure reopens this ADR: the
  delivery states after each result, backoff with and without jitter (REQ-REL-006), the 5 attempts
  before `DEAD` (REQ-FUNC-057) and business endpoints unaffected with both providers down
  (REQ-REL-003).

## Pros and Cons of the Options

### Resilience4j core, JDK HttpClient, stored backoff

- Good: explicit, testable building blocks; each concern lives where its state belongs.
- Bad: a little more wiring than annotations.

### Resilience4j Spring Boot starter and annotations

- Good: declarative and concise.
- Bad: depends on the starter supporting Boot 4.1 (RISK-005); annotations act through proxies and
  exceptions, while Warden's results are values; in-memory retries would not survive a restart.

### Failover across attempts

- Good: simpler attempt logic.
- Bad: a transient failure of the primary delays the delivery by a whole backoff interval.

### No circuit breaker

- Good: no extra component.
- Bad: every attempt keeps hitting a provider that is down, paying its timeout each time.