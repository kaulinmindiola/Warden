# ADR-007: Revocation denylist in Redis with PostgreSQL fallback and reconciliation

- Status: accepted
- Date: 2026-10-29
- Deciders: Kaulin Mindiola
- Related: REQ-FUNC-018, REQ-REL-007, REQ-REL-008, REQ-REL-009, REQ-AVAIL-001, REQ-OBS-001,
  G-02, DEC-036, DEC-045, DRISK-01; SDD V09, V11, V14-A7, V17, V19, V23; ADR-006, ADR-010,
  ADR-014, ADR-020

## Context and Problem Statement

Access tokens live 10 minutes and carry the session id (`sid`). A revoked session must be rejected
on the very next request (REQ-FUNC-018, G-02), so every authenticated request checks its session.
PostgreSQL holds the truth (`auth_session.revoked_at`), but asking it on every request puts a query
on the hot path. Redis, shared by every instance, answers faster, but it can fail. The SRS requires
that a Redis outage neither locks every user out nor lets revoked sessions in (REQ-REL-007), and
that a PostgreSQL outage fails closed (REQ-REL-009). The SDD found a gap the SRS does not state: a
session revoked while Redis is down would be accepted again once Redis returns without its key.

How are revocations checked, and how does Warden behave when Redis, PostgreSQL or both fail?

## Decision Drivers

- Revocation effective on the next request, on every instance.
- No revoked session accepted at any time, during or after an outage.
- A Redis outage must not stop authentication; PostgreSQL is the source of truth and fails closed.
- Failures detected in bounded time: a hung dependency must not hang every request.
- Readiness reflects only what makes an instance unable to serve.
- Deterministic tests against real infrastructure, with no new components (CON-007).

## Considered Options

1. A Redis denylist of revoked `sid` values with a TTL, a per-instance degraded mode that asks
   PostgreSQL, and reconciliation on recovery and every 30 seconds.
2. PostgreSQL on every request, without Redis.
3. A Redis denylist that fails closed (503) whenever Redis fails.
4. A Redis denylist that fails open (tokens accepted) whenever Redis fails.
5. Short-lived tokens only, without a per-request check.

## Decision Outcome

Chosen option: **1**.

**Denylist.** After the PostgreSQL commit that revokes a session, `warden:revoked:{sid}` is written
with a TTL of 630 s: the access-token lifetime (600 s) plus the clock skew (30 s), after which every
token of the session has expired anyway. The validator of the access-token decoder asks Redis:
revoked → 401, not revoked → accepted.

**Degraded mode (SDD V14-A7).** Redis errors never propagate. The first error, a command timeout or
a connection failure, puts the instance in degraded mode: lookups answer "unknown" at once and the
validator asks PostgreSQL, failing closed, so a revoked or unknown session is rejected. Revocations
are still written to Redis; if the write fails, PostgreSQL already holds the truth. The mode belongs
to each instance, and each instance detects failures by itself.

**Recovery and reconciliation.** Every 5 seconds a degraded instance pings Redis. When Redis
answers, the instance rewrites every session revoked in the last 630 s with the TTL it has left, and
only then leaves degraded mode. A failure counter, guarded by a lock, keeps the instance degraded
if any Redis error happened during the rewrite, so a revocation lost meanwhile is never forgotten.
Healthy instances run the same rewrite every 30 seconds, which bounds to 30 s the gap left by a
Redis write lost on one instance while the others still read Redis (DRISK-01). Every instance runs
these workers; rewriting the same keys twice is harmless.

**Timeouts (SDD V23).** Spring Data Redis commands fail after 200 ms instead of Lettuce's default of
60 s, and connections after 1 s. The rate limiter uses its own connection with the same command
timeout (ADR-014). Hikari hands out a PostgreSQL connection within 2 s or fails.

**PostgreSQL unavailable (REQ-REL-009).** A failure whose JDBC cause is a connection exception, a
Hikari timeout or an SQLSTATE of class `08` or `57P0x` becomes 503 `service-unavailable` with
`Retry-After: 5`. Spring MVC errors get it from the problem-details advice, workspace authorization
from its interceptor, and failures inside the servlet filters, such as the token check when Redis
is also down, from a dedicated filter placed before the security chain. Nothing is answered from a
cache and no workspace request is authorized. Redis failures are never classified as database
failures, although Spring reports both as `DataAccessResourceFailureException`.

**Redis and PostgreSQL unavailable (REQ-REL-007).** The token check falls back to PostgreSQL, which
fails, so authenticated requests answer 503.

**Health (REQ-AVAIL-001).** The `readiness` group contains `readinessState` and `db`: it is DOWN
only when PostgreSQL does not answer, or while the application is shutting down. Redis is left out
because its failure is degraded, not fatal. `liveness` reports only the application state.

**Observability.** The gauges `warden_revocation_degraded` and `warden_ratelimit_degraded` are 1
while an instance is degraded. Entering degraded mode logs one warning and leaving it one
information line. A request denied by a database outage logs a warning with the JDBC exception
class and SQLSTATE, never its message.

**Testing technique.** Outages are simulated by freezing the shared Testcontainers containers with
`docker pause`, not with `stop()` and `start()`: a restarted container gets a new mapped port,
which would break every cached Spring context. A frozen server accepts connections but never
answers, which is the harder failure: timeouts rather than refused connections. Pooled PostgreSQL
connections are evicted after the pause, because a query on an open connection to a frozen server
has no timeout (see Consequences). Background workers are disabled on the test classpath
(`warden.scheduling.enabled=false`), and tests call recovery and reconciliation directly.

### Consequences

- Good: revocation applies on the next request with one Redis round trip; during a Redis outage it
  still applies, at the cost of one PostgreSQL query per request.
- Good: no revoked session is accepted after an outage, even if Redis lost its data.
- Good: failures are detected within 200 ms (Redis) or 2 s (PostgreSQL); after a Redis failure only
  the first request pays the timeout.
- Bad: while degraded, every authenticated request queries PostgreSQL; the pool bounds that load
  (ADR-010).
- Bad: a Redis write lost on one instance can let a revoked session through the other, healthy
  instances for up to 30 s (DRISK-01, accepted).
- Bad: a query on a pooled connection to a server that stops answering without closing the
  connection waits on the TCP stack. There is no socket timeout, because a global one would also
  cut legitimate long statements. Hikari validates a connection idle for more than 500 ms before
  handing it out, which limits the exposure to connections reused immediately. To be revisited if
  the load test shows that it matters.
- Bad: rate-limit counts made locally during a Redis outage are not copied back to Redis (ADR-014).
- Neutral: degraded modes belong to each instance, and each instance recovers on its own.

### Confirmation

- `RedisRevocationStoreTest`, `RevocationReconcilerTest`: degraded mode, remaining TTL, leaving
  degraded mode only after a complete rewrite, the failure counter, periodic reconciliation and the
  gauge.
- `RevocationDegradationIT` (Redis frozen): revoked → 401 and active → 200; a session revoked during
  the outage stays rejected after recovery; reconciliation restores a lost key; expired revocations
  are not rewritten.
- `RevocationFallbackIT`, `SessionRevocationIT`: the PostgreSQL fallback and immediate revocation.
- `DatabaseUnavailableTest`, `DatabaseUnavailableFilterTest`, `ProblemDetailsContractIT`,
  `WorkspaceAuthorizationInterceptorTest`: classification of database outages and 503 with
  `Retry-After: 5`.
- `DependencyOutageIT`: with PostgreSQL frozen, register, login, refresh, the session list and a
  workspace read answer 503 and none answers 2xx (REQ-REL-009); with Redis and PostgreSQL frozen,
  authenticated requests answer 503 (REQ-REL-007); readiness is DOWN only while PostgreSQL is down
  and liveness stays UP (REQ-AVAIL-001).
- `RateLimitFallbackIT`, `FailoverBucketSourceTest`: rate limits without Redis (REQ-REL-008,
  ADR-014).

## Pros and Cons of the Options

### Redis denylist with degraded mode and reconciliation

- Good: a fast path in Redis, correctness from PostgreSQL, no lockout during a Redis outage.
- Bad: the most moving parts: a mode per instance, a recovery loop and a reconciliation job.

### PostgreSQL on every request

- Good: the simplest option, and always consistent.
- Bad: one query per authenticated request on the hot path (REQ-PERF-003), and it removes no
  component, because Redis is required for the rate limits anyway.

### Fail closed when Redis fails

- Good: simple and safe.
- Bad: a Redis outage locks every user out, against REQ-REL-007.

### Fail open when Redis fails

- Good: always available.
- Bad: revoked sessions are accepted during the outage, against REQ-FUNC-018 and G-02.

### Short-lived tokens only

- Good: no per-request lookup.
- Bad: a revocation would take effect only when the token expires, up to 10 minutes later, against
  REQ-FUNC-018.