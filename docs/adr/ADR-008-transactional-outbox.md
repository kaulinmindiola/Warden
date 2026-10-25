# ADR-008: Transactional outbox with at-least-once dispatch

- Status: proposed
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: REQ-REL-001, REQ-REL-002, REQ-FUNC-061, REQ-FUNC-063, REQ-MAINT-005, DEC-007, DEC-018, G-03; SDD V06, V10, V13, V16; ADR-011, ADR-015, ADR-017

## Context and Problem Statement

State changes in `iam` and `workspace` produce events that `notification` turns into e-mails.
Every event of a committed change must be published at least once, even if the process stops
right after the commit (REQ-REL-001). With two or more instances, each event must produce its
deliveries exactly once per recipient (REQ-REL-002). Event schemas are versioned (REQ-MAINT-005),
and sealed secrets must be removed from the event once they are copied into a delivery (ADR-011).

How are events stored and dispatched so that they are atomic with the state change and safe
with several instances?

## Decision Drivers

- No dual write: the event and the state change commit or roll back together.
- At-least-once dispatch that survives crashes; idempotent consumers.
- Safe concurrent dispatch from several instances without an external coordinator.
- Control over the stored payload: an explicit type and schema version, and secret stripping.
- No additional infrastructure beyond PostgreSQL and Redis (CON-007, proportionality).

## Considered Options

1. Own `outbox_event` table, written in the business transaction, plus a polling dispatcher
   that claims rows with `FOR UPDATE SKIP LOCKED` and runs the handler in the same transaction.
2. Spring Modulith event publication registry with transactional application module listeners.
3. In-memory events published after commit, without persistence.
4. An external message broker (Kafka, RabbitMQ) written to by the services.

## Decision Outcome

Proposed option: **1, own outbox table with a `SKIP LOCKED` dispatcher**.

**Write side (implemented in step 1.7):**

- `shared.outbox.OutboxPublisher.publish()` requires an active transaction
  (`Propagation.MANDATORY`). Without one it fails, so an event cannot exist without its state
  change, nor the reverse.
- Envelope (SDD V10): UUIDv7 `eventId`, `module.EventName` type, `schemaVersion` starting at 1,
  `occurredAt` from the application clock, nullable `workspaceId`, `traceId`, and a JSON object
  payload serialized with the application JSON mapper.
- Table `outbox_event` with a partial index `(occurred_at) WHERE published_at IS NULL`. No RLS:
  only workers read it.

**Dispatch side (phase 4, steps 4.4 and 4.5):**

- Every 500 ms, `SELECT … WHERE published_at IS NULL ORDER BY occurred_at FOR UPDATE SKIP LOCKED
  LIMIT 100`. The handler creates the deliveries with `ON CONFLICT DO NOTHING` on
  `(event_id, recipient_key, channel)`, then sets `published_at` and strips `payload.secret`,
  all in one transaction.
- Published events are purged after 7 days (REQ-FUNC-063).

This ADR becomes `accepted` in step 4.5, when the dispatcher passes the restart test of
REQ-REL-001 and the two-instance, 1,000-event test of REQ-REL-002.

### Consequences

- Good: atomicity comes from a single PostgreSQL transaction; there is no dual write.
- Good: `SKIP LOCKED` lets every instance dispatch concurrently without double-claiming a row.
- Good: the payload is explicit JSON with a type and a version, independent of Java class names,
  so upcasters for version N−1 stay possible.
- Bad: dispatch is at-least-once. A crash after the handler and before the commit repeats the
  work, so consumers must be idempotent (the unique delivery key provides this).
- Bad: polling adds up to one interval of latency and constant light load on PostgreSQL.
- Bad: the table grows with traffic and needs the purge job.

### Confirmation

- Implemented: `OutboxPublisherIT`. Publishing outside a transaction fails. A rollback removes
  both the state change and the event. A commit stores every envelope field, and a sealed secret
  keeps the `{ciphertext, keyId, expiresAt}` shape.
- Pending for acceptance: the restart test (REQ-REL-001), the two-instance test (REQ-REL-002),
  and the purge with a controlled clock (REQ-FUNC-063).

## Pros and Cons of the Options

### Own outbox table with a `SKIP LOCKED` dispatcher

- Good: full control of the schema, claiming, secret stripping and retention, all with
  PostgreSQL only.
- Bad: more code to own and test than a framework feature.

### Spring Modulith event publication registry

- Good: built into the framework the project already uses for module verification; little code.
- Bad: publications are tracked per listener and are tied to Java event types, while Warden
  needs explicit type names, schema versions and payload rewriting (secret stripping).
- Bad: the coordination of resubmission across several instances would have to be verified and
  possibly supplemented. The claiming behaviour Warden needs is easier to guarantee and test with
  its own query.

### In-memory events after commit

- Good: trivial.
- Bad: events are lost if the process stops between the commit and the publication; violates
  REQ-REL-001.

### External message broker

- Good: a standard solution at scale.
- Bad: writing to the database and the broker is a dual write unless an outbox is added anyway.
  It also adds infrastructure that the reference environment does not need.