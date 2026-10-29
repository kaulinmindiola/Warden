# ADR-014: Rate-limiting mechanism and local fallback

- Status: accepted
- Date: 2026-10-29
- Deciders: Kaulin Mindiola
- Related: REQ-SEC-006, REQ-SEC-007, REQ-SEC-008, REQ-SEC-009, REQ-FUNC-007, REQ-FUNC-033,
  REQ-REL-008, REQ-OBS-001, BR-007, DEC-005, DEC-028, DEC-031, DEC-045, ASM-SDD-01, RISK-012;
  SDD V06, V09, V14-A2, V16, V17, V18, V19, V23; ADR-010, ADR-019, ADR-020

## Context and Problem Statement

Warden must reject abuse of its sensitive endpoints with exact, normative thresholds: 5 failed
logins per e-mail in 15 minutes, 20 logins per IP per minute, 3 reset requests per e-mail and 10
per IP per hour, 60 refreshes per IP per minute, 3 verification resends per account per hour, and
50 invitations per workspace and 100 per inviter per day. The limits must hold across instances,
must not reveal whether an e-mail has an account, must keep working when Redis fails
(REQ-REL-008), and must be testable deterministically. How are the limits counted, where is their
state kept and how is a request identified?

## Decision Drivers

- The thresholds of the SRS are normative; "5 failures within 15 minutes, then 429 until the window
  allows it" must hold as written.
- State shared by every instance (REQ-DIST-001), with atomic updates under concurrency.
- No e-mail address, IP or id stored in Redis (SDD V06, V18).
- A limit must not become an account-existence oracle (BR-007).
- Degradation without Redis instead of failing open or closed (DEC-045).
- Deterministic tests with the controllable clock; no new infrastructure (CON-007).

## Considered Options

1. Bucket4j token buckets refilled in full once per window, stored in Redis through its
   compare-and-swap proxy on Lettuce, with instance-local buckets as the fallback.
2. Hand-written fixed-window counters with `INCR` and `EXPIRE` (the POC-001 fallback).
3. Sliding-window logs or counters in Redis sorted sets.
4. Token buckets with gradual ("greedy") refill.
5. Limits enforced by a reverse proxy or API gateway.

## Decision Outcome

Chosen option: **1**.

**Port.** `shared.ratelimit.RateLimiter` offers `check(policy, subject)`, which reads a bucket
without consuming it, and `consume(policy, subject)`. Both return `Decision(allowed, retryAfter)`;
`requireAllowed()` turns a rejection into the `rate-limited` problem (429) with a `Retry-After`
header in whole seconds, rounded up so that a client waiting exactly that long is never early.

**Window semantics (resolves ASM-SDD-01).** Each policy is a bucket of `capacity` tokens refilled
in full once per `window`. Windows are anchored at the first request of each subject, not at the
calendar: "per day" means 24 hours from the first invitation, not since midnight. Within one window
the (capacity + 1)-th request is rejected, exactly as the SRS states; across a boundary at most
2 × capacity requests fit in a span of two windows. A greedy refill would return one failed-login
token every 3 minutes and admit the 6th attempt after 3 minutes, which the SRS forbids.

**Policies.** Configured under `warden.ratelimit.*`; `application.yaml` holds the SRS values and a
missing limit stops the application at startup.

| Policy | Subject | Limit | Applied in | Spent when |
|--------|---------|-------|------------|------------|
| `login-failures-per-email` | normalized e-mail | 5 / 15 min | `LoginService` | checked before Argon2; consumed only on failure |
| `login-per-ip` | client IP | 20 / 1 min | `LoginService` | every login after the e-mail check |
| `password-reset-per-ip` | client IP | 10 / 1 h | `PasswordResetService` | every request, before the e-mail is read |
| `password-reset-per-email` | normalized e-mail | 3 / 1 h | `PasswordResetService` | every well-formed request |
| `refresh-per-ip` | client IP | 60 / 1 min | `RefreshService` | every request, before the token is read |
| `verification-resend-per-account` | user id | 3 / 1 h | `EmailVerificationService` | every request |
| `invitations-per-workspace` | workspace id | 50 / 1 d | `InvitationService` | under the workspace lock, after validation, authority and member checks |
| `invitations-per-inviter` | user id | 100 / 1 d | `InvitationService` | same, after the workspace limit |

- **Enumeration.** Unknown e-mails share the exact behaviour of existing ones, so a 429 reveals
  nothing. Malformed e-mails cannot be accounts and are limited by IP only. The login check happens
  before Argon2, so a blocked e-mail is rejected even with the right password.
- **Invitations.** Only invitations that would be sent spend the quotas: a 400, 403 or 409 costs
  nothing, so nobody can exhaust a workspace's quota with invalid requests. If the workspace limit
  passes and the inviter limit rejects, one workspace token is lost; the error is on the safe side.

**Keys and storage.** Redis keys are `warden:rl:{policy}:{hmac(subject)}` with HMAC-SHA-256 under
`WARDEN_HMAC_KEY`; rotating that key resets every bucket. Each key expires when its bucket would be
full again plus 10 seconds. The limiter uses its own Lettuce connection, opened on first use so
the application starts and stays ready without Redis, with a 200 ms command timeout
(`warden.ratelimit.redis-command-timeout`, SDD V23) so that a failing Redis is detected quickly.
Bucket4j reads time from the application `Clock`.

**Client IP.** The IP is the TCP peer address (`getRemoteAddr()`). `X-Forwarded-For` is ignored:
the reference environment has no proxy, and any client could forge the header to escape every
per-IP limit. Trusted-proxy configuration is in the 1.1 backlog.

**Fallback without Redis (REQ-REL-008, implemented in step 3.5).** When Redis fails, the same
policies are enforced with instance-local buckets behind the same `BucketSource` interface. With
several instances the effective limit is multiplied by their number (RISK-012, accepted for 1.0).

**Observability.** Every rejection increments `warden_ratelimit_rejections_total{policy}`; the
counters exist with zero from startup. No subject appears in tags (REQ-OBS-003).

### Consequences

- Good: the SRS thresholds hold literally, with atomic counting shared by every instance.
- Good: one library already validated in POC-001, behind a port with a single adapter today.
- Good: no personal data in Redis, and a 429 never distinguishes existing from unknown e-mails.
- Bad: up to 2 × capacity requests across a window boundary; accepted because each window still
  honours the SRS threshold.
- Bad: one Redis round trip per limited request; for invitations it happens while the workspace row
  is locked.
- Bad: clients behind one NAT share the per-IP limits.
- Bad: concurrent failed logins for one e-mail can all pass the check before any of them consumes,
  so a burst may exceed 5 by its concurrency; the per-IP limit bounds that burst.
- Bad: until step 3.5, a Redis failure makes the limited endpoints answer 500.
- Neutral: every test request comes from 127.0.0.1, so the test classpath raises only the per-IP
  capacities; `IamRateLimitIT` restores the SRS values in its own context with one random client
  address per test, and the shipped defaults are checked against the main `application.yaml`.

### Confirmation

- `BucketRateLimiterTest`: capacity, refill only after the window, a shrinking `Retry-After`,
  `check` never consumes, independent buckets per subject and policy, HMAC keys without the
  subject, rejection counters per policy.
- `RedisRateLimiterIT`: exhaustion and recovery in Redis with the test clock, hashed keys with an
  expiry, the metric, and two independent instances sharing one bucket.
- `RateLimitPropertiesTest`: the shipped defaults are the SRS values; every policy binds from its
  property; a missing or invalid limit stops startup.
- `ProblemDetailsContractIT`, `ProblemExceptionTest`, `RateLimiterDecisionTest`: 429 with
  `Retry-After` in whole seconds, rounded up.
- `IamRateLimitIT`: REQ-SEC-006 (including unknown e-mails and successful logins not counting),
  REQ-SEC-007, REQ-SEC-008, REQ-SEC-009 and REQ-FUNC-007.
- `InvitationRateLimitIT`: REQ-FUNC-033, including a quota shared by admins and rejected requests
  that spend nothing.
- Step 3.5 adds the confirmation of the local fallback (REQ-REL-008).

## Pros and Cons of the Options

### Bucket4j, full refill per window, Redis with local fallback

- Good: exact window semantics, atomic compare-and-swap, a controllable clock, expiring keys.
- Bad: one more library and a dedicated Redis connection.

### `INCR` and `EXPIRE` fixed windows

- Good: no library; trivial to read.
- Bad: the retry time, the non-consuming check and the local fallback all have to be written by
  hand, and expiry follows Redis time, not the application clock, so time-based tests need real
  waits.

### Sliding windows in sorted sets

- Good: no boundary burst.
- Bad: one entry per request in Redis, more complex scripts, and no requirement asks for it.

### Greedy refill

- Good: smoother traffic.
- Bad: admits the 6th failed login after 3 minutes, against REQ-SEC-006.

### Reverse proxy or gateway

- Good: rejects abuse before it reaches the application.
- Bad: it cannot see login outcomes, normalized e-mails or workspace ids, and it adds a component
  that CON-009 does not include.