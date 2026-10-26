# ADR-019: Response-time equalization against account enumeration

- Status: accepted
- Date: 2026-10-26
- Deciders: Kaulin Mindiola
- Related: REQ-SEC-010, REQ-FUNC-002, REQ-FUNC-009, REQ-FUNC-019, REQ-PERF-001, BR-007; SDD V11, V14-A2, V14-A3; ADR-010

## Context and Problem Statement

Registration, login and the password reset request must not reveal whether an e-mail has an
account (BR-007). Their bodies and status codes are already identical, but their duration could
still differ: an existing account means more database work, an unknown e-mail on login skips
Argon2. REQ-SEC-010 requires the median response times of the two cases to differ by at most 10 %.
How are these responses made indistinguishable in time?

## Decision Drivers

- No branch may be measurably faster than the other, including validation failures.
- No extra infrastructure, and no blocking of scarce threads.
- A configurable value, so it can be calibrated without code changes.
- Testable without statistical runs on every build.

## Considered Options

1. The same expensive work in both branches, plus a configurable minimum response time.
2. The same work in both branches only, with no minimum time.
3. A random delay added to every response.
4. Accepting the request and processing it asynchronously, answering at once.

## Decision Outcome

Chosen option: **1**.

- **Login (V14-A2):** an unknown e-mail is verified against a fixed dummy Argon2 hash
  (`PasswordHasher.matchesDummy`), so both branches run one Argon2 verification. No floor is
  applied; Argon2 dominates both branches.
- **Registration and the password reset request (V14-A3):** both branches already do comparable
  work (registration always hashes the password; the reset request always generates a token), and
  the controller runs the use case inside `ResponseFloor`, which returns no sooner than
  `warden.security.response-floor` after the request started (default 250 ms). Time is measured
  with a monotonic clock. The wait is in a `finally` block, so a validation error or a failure
  also takes the floor. On virtual threads the wait holds no carrier thread.
- The floor must exceed the slow path's p99. Calibrating it with the REQ-SEC-010 analysis (at
  least 200 samples per case) is deferred; until then 250 ms is the baseline.

### Consequences

- Good: the timing guarantee does not depend on both branches doing exactly the same work, only on
  the slow path staying below the floor.
- Good: one small component and one property; no new dependency.
- Bad: every registration and reset request takes at least 250 ms; REQ-PERF-001 targets must not
  include these two endpoints, or must account for the floor.
- Bad: if the slow path exceeds the floor under load, its extra time leaks again; the calibration
  and a p99 alert are the safeguard.
- Neutral: the test profile sets the floor to zero to keep the suite fast; a dedicated test sets
  its own value.

### Confirmation

- `ResponseFloorTest`: a fast action waits exactly the remainder; a slow or exact action is not
  delayed; a failing action still waits and its exception propagates; zero disables the wait; a
  negative floor is rejected; an interrupted wait keeps the result and the interrupt flag; the
  default is 250 ms and the value is configurable.
- `ResponseFloorIT`: with the floor set to 300 ms, registering a new and an existing e-mail and
  requesting a reset for an existing and an unknown e-mail all take at least 300 ms.
- `RegistrationIT` and `PasswordResetIT`: both branches return identical responses.

## Pros and Cons of the Options

### Equal work plus a minimum time

- Good: robust against small differences in database work.
- Bad: adds fixed latency to two endpoints.

### Equal work only

- Good: no added latency.
- Bad: fragile; any extra insert, event or seal in one branch reopens the side channel.

### Random delay

- Good: simple.
- Bad: noise averages out; the median difference survives enough samples.

### Asynchronous processing

- Good: constant, fast responses.
- Bad: needs a queue and worker for synchronous use cases, and validation errors could no longer
  be reported in the response.