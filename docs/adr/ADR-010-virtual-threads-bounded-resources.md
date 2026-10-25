# ADR-010: Virtual threads with explicitly bounded resources

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: REQ-PERF-001 to REQ-PERF-005, REQ-SEC-003; SDD V16, V23; ADR-009, ADR-015

## Context and Problem Statement

Warden runs on Java 21. Most request time is spent waiting: on PostgreSQL, on Redis and, in
phase 4, on e-mail providers. One operation is the opposite: Argon2id password hashing uses
19 MiB of memory and tens of milliseconds of CPU per computation (REQ-SEC-003).

Virtual threads make waiting cheap, but they also remove the implicit limit that a fixed pool
of platform threads used to impose. Without that limit, a burst of logins would start one
Argon2 computation per request at once. Which threading model does Warden use, and where are
the limits?

## Decision Drivers

- Simple blocking code in services and repositories (no reactive stack).
- Throughput under I/O-bound load (REQ-PERF-001 to REQ-PERF-005).
- Bounded memory: the number of concurrent Argon2 computations must be capped.
- Bounded pressure on PostgreSQL and on external providers.

## Considered Options

1. Platform threads with the default Tomcat pool.
2. Virtual threads with no additional limits.
3. Virtual threads, with an explicit limit for each scarce resource.

## Decision Outcome

Chosen option: **3, virtual threads with explicit limits per resource**.

- `spring.threads.virtual.enabled=true`: HTTP requests and the application task executor run on
  virtual threads.
- **Argon2:** `BoundedPasswordHasher` lets at most `warden.security.argon2.max-concurrency`
  computations (default 4) run at once, through a fair semaphore. Other requests wait for a
  permit, which costs almost nothing on a virtual thread. Peak hashing memory is about 4 × 19
  MiB.
- **PostgreSQL:** the Hikari pool is the database limiter. A virtual thread waits for a
  connection instead of opening one more. Argon2 runs before the transaction starts, so no
  connection is held while a password is hashed.
- **E-mail providers (phase 4):** a semaphore caps concurrent provider calls, and each call has
  its own time limit (ADR-009).

### Consequences

- Good: plain blocking code scales with I/O waits; a slow dependency does not exhaust a thread
  pool.
- Good: each limit is explicit, configurable and tested, instead of being a side effect of a
  thread-pool size.
- Bad: on Java 21, a virtual thread that blocks inside a `synchronized` block pins its carrier
  thread. Warden's code uses no `synchronized` blocking sections (the semaphore and the JDK
  locks do not pin); third-party libraries are watched, and Java 24 removes this limitation
  (JEP 491).
- Bad: thread-local caches lose their value with one thread per request, so Warden does not
  cache `Mac`, `Cipher` or `MessageDigest` instances in thread locals; it creates one per call.
- Neutral: measured performance targets are verified with the load tests that REQ-PERF-* defer.

### Confirmation

- `RuntimeDefaultsIT#applicationTasksRunOnVirtualThreads`: application tasks run on virtual
  threads.
- `BoundedPasswordHasherTest#neverRunsMoreHashesAtOnceThanAllowed`: with a limit of 2 and 8
  concurrent requests, no more than 2 computations overlap.
- `AccountPersistenceIT#theConfiguredHasherUsesTheArgon2idParameters`: production parameters.
- Planned: the provider-call limit in phase 4.

## Pros and Cons of the Options

### Platform threads with the Tomcat pool

- Good: familiar; the pool size implicitly limits Argon2.
- Bad: every wait on a dependency holds a scarce thread; one slow provider or query can exhaust
  the pool. The implicit limit is too coarse for Argon2.

### Virtual threads without limits

- Good: maximum concurrency with no configuration.
- Bad: unbounded Argon2 memory and unbounded pressure on the database and providers.

### Virtual threads with explicit limits

- Good: cheap waiting plus deliberate, testable caps where resources are scarce.
- Bad: every new scarce resource needs its own limit, decided explicitly.