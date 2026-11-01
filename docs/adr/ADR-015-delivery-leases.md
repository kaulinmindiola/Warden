# ADR-015: Delivery scheduling and claiming with leases

- Status: accepted
- Date: 2026-11-01
- Deciders: Kaulin Mindiola
- Related: REQ-REL-002, REQ-REL-003, REQ-AVAIL-003, REQ-FUNC-057, REQ-FUNC-060, REQ-FUNC-062,
  REQ-SEC-005, REQ-DIST-001; SDD V13, V14-A5, V15, V16; ADR-008, ADR-009, ADR-010

## Context and Problem Statement

Deliveries are created inside the outbox dispatch transaction (ADR-008) and sent later to an
external provider that may take up to 5 s per call, twice per attempt with failover (ADR-009).
Several instances run the same worker. A delivery must be sent by one instance at a time, must not
hold a database transaction open during the provider calls, and must not be lost when an instance
dies in the middle of a send or the whole application restarts (REQ-AVAIL-003).

How are due deliveries claimed, sent and recovered?

## Decision Drivers

- No database transaction or row lock held across network calls.
- Mutual exclusion between instances without extra infrastructure.
- Recovery after a crash without operator action.
- Business operations unaffected by provider outages (REQ-REL-003).
- Testable with a controllable clock.

## Considered Options

1. `FOR UPDATE SKIP LOCKED` claim that sets a lease, committed at once; send outside any
   transaction; record the result against the state re-read under lock.
2. One transaction per delivery holding the row lock while calling the providers.
3. A distributed scheduler lock (ShedLock) so only one instance runs the worker.
4. An external queue (RabbitMQ, SQS) with visibility timeouts.

## Decision Outcome

Chosen option: **1**.

- **Claim.** Every second each instance runs `UPDATE … SET lease_until = now + 30 s WHERE id IN
  (SELECT … WHERE status IN ('PENDING','RETRYING') AND next_attempt_at <= now AND (lease_until IS
  NULL OR lease_until < now) ORDER BY next_attempt_at LIMIT 16 FOR UPDATE SKIP LOCKED) RETURNING …`
  and commits. Concurrent claims are disjoint; a leased delivery is skipped until its lease lapses.
- **Send.** Up to 8 deliveries at a time on virtual threads (ADR-010): the pinned template is
  rendered, the sealed token opened into its link, and the providers called with failover.
- **Record.** In a new transaction the state is re-read with `FOR UPDATE`; a delivery cancelled in
  the meantime stays `CANCELLED`; otherwise the state machine (`Delivery`, REQ-FUNC-058) applies the
  result, stores `next_attempt_at` for a retry, releases the lease and drops the token once it is
  no longer needed.
- **Recovery.** All state lives in `notification_delivery`. A worker that dies after claiming
  leaves a lease that lapses after 30 s; any instance then claims the delivery again. A restart
  loses nothing: due `PENDING` and `RETRYING` rows are claimed by whichever instance runs next.
- **Idempotency towards providers.** Each send carries the delivery id as idempotency key, so a
  provider can drop the second copy of a delivery sent twice after a lapsed lease.

### Consequences

- Good: no lock or transaction is held during provider calls; slow providers cost worker time only.
- Good: no new infrastructure; PostgreSQL provides exclusion and durability.
- Good: business requests never wait for a provider: they only write the outbox.
- Bad: delivery towards the provider is at-least-once. A send that outlives its lease, or a crash
  between the provider's acceptance and the recording, sends the message again with the same key.
  The simulators do not deduplicate; a real provider is expected to.
- Bad: the lease must exceed the longest attempt (2 × 5 s plus rendering); it is 30 s.
- Bad: one claim query per instance per second, cheap thanks to the partial index on
  `next_attempt_at`.

### Confirmation

- `DeliveryWorkerIT`: a claimed delivery is leased for 30 s and only claimed again once the lease
  lapses; a delivery cancelled while being sent stays `CANCELLED`; backoff, `DEAD` after 5 attempts,
  permanent rejection and token removal.
- `DeliveryResilienceIT`: with both providers down (timeout and 503), login, refresh, invitation and
  note operations answer as usual and the delivery stays `RETRYING` (REQ-REL-003); a delivery left
  `RETRYING` is sent by an application instance started afterwards (REQ-AVAIL-003); after a lapsed
  lease another run sends the delivery and both sends carry the same idempotency key.
- To be re-confirmed in step 4.14 with two instances and 1,000 events (REQ-REL-002).

## Pros and Cons of the Options

### Lease claim, send outside the transaction

- Good: short transactions, crash recovery built in.
- Bad: at-least-once towards providers.

### Row lock held during the send

- Good: a crash rolls back and frees the row immediately.
- Bad: connections and locks held for up to 10 s per delivery; the pool becomes the bottleneck.

### ShedLock

- Good: simple mental model.
- Bad: only one instance works at a time; still needs per-delivery recovery.

### External queue

- Good: mature visibility-timeout semantics.
- Bad: a new runtime dependency and a dual write between the database and the queue.