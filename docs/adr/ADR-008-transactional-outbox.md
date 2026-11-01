# ADR-008: Transactional outbox with at-least-once dispatch

- Status: accepted
- Date: 2026-10-31 (proposed 2026-10-25)
- Deciders: Kaulin Mindiola
- Related: REQ-REL-001, REQ-REL-002, REQ-FUNC-061, REQ-FUNC-062, REQ-FUNC-063, REQ-MAINT-005, DEC-007, DEC-018, G-03; SDD V04 (R5), V06, V07, V10, V13, V16; ADR-011, ADR-015, ADR-017

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

Chosen option: **1, own outbox table with a `SKIP LOCKED` dispatcher**.

**Write side (step 1.7).**

- `shared.outbox.OutboxPublisher.publish()` requires an active transaction
  (`Propagation.MANDATORY`). Without one it fails, so an event cannot exist without its state
  change, nor the reverse.
- Envelope (SDD V10): UUIDv7 `eventId`, `module.EventName` type, `schemaVersion` starting at 1,
  `occurredAt` from the application clock, nullable `workspaceId`, `traceId`, and a JSON object
  payload serialized with the application JSON mapper.
- Table `outbox_event`, without RLS: only workers read it.

**Dispatch side (steps 4.2 to 4.5).**

- `shared.outbox.OutboxDispatcher.dispatchBatch()` runs in the caller's transaction
  (`MANDATORY`): `SELECT … WHERE published_at IS NULL ORDER BY dispatch_order LIMIT 100 FOR UPDATE
  SKIP LOCKED`, hands each event to every `OutboxEventHandler`, then sets `published_at` and strips
  `payload.secret` in the same transaction.
- The 500 ms tick and `runOnce()` live in `notification.application.worker.OutboxDispatchWorker`,
  which opens that transaction with `TenantTransactions.system(…)`.
- `notification.application.NotificationEventHandler` creates the deliveries with `ON CONFLICT
  DO NOTHING` on `(event_id, recipient_key, channel)` and applies the cancellations of
  `InvitationRevoked` and `WorkspaceDeleted` (REQ-FUNC-062).

### Deviations from the SDD, decided during implementation

1. **The tick lives in the notification worker, not in `shared`.** SDD V13 draws the loop in the
   shared dispatcher, but the handler needs the system context to write `notification_delivery`
   under RLS, and R5 (FF-03) allows only `notification.application.worker` to open it. The
   dispatcher therefore requires an existing transaction and the worker opens it. R5 stays intact
   and `shared` still depends on no module.
2. **One savepoint per event.** A handler that throws rolls back only that event's writes; the
   event stays pending with its secret, the rest of the batch is published, and
   `warden.outbox.dispatch.failures` counts the failure. Without it, one event that always fails
   would roll back every batch and stop the outbox. Limitation: an exception escaping from a
   `@Transactional` bean marks the whole transaction for rollback; the dispatcher detects it and
   the batch is retried. Handlers therefore write through `JdbcClient` and open no transactions,
   as the `OutboxEventHandler` contract states.
3. **Dispatch in insertion order (V17).** `occurred_at` and the UUIDv7 id give no order within a
   millisecond, so a cancellation could be handed over before the invitation it cancels. An
   identity column `dispatch_order` orders the events as they were written.
4. **Ticks switched off per bean.** Every `@Scheduled` method lives in a schedule bean marked
   `@ConditionalOnSchedulingEnabled`. `warden.scheduling.enabled=false` once skipped only the
   application's `@EnableScheduling`, but a library on the classpath enables scheduling as well,
   so the workers kept ticking in tests and dispatched the outbox under other tests.
5. **Deliveries keep the key id of the sealed token** (`secret_key_id`) next to its ciphertext,
   which ADR-011 needs for key rotation and the SDD data model did not list.

### Consequences

- Good: atomicity comes from a single PostgreSQL transaction; there is no dual write.
- Good: `SKIP LOCKED` lets every instance dispatch concurrently without double-claiming a row.
- Good: the payload is explicit JSON with a type and a version, independent of Java class names,
  so upcasters for version N−1 stay possible.
- Good: deliveries and the publication of their event commit together, so event → delivery is
  effectively exactly-once.
- Bad: dispatch is at-least-once. A crash after the handler and before the commit repeats the
  work, so consumers must be idempotent (the unique delivery key provides this).
- Bad: polling adds up to one interval of latency and constant light load on PostgreSQL.
- Bad: an event whose handler always fails is retried every tick until fixed; it is visible
  through the failure counter and the error log, which names the event and type only.
- Bad: the table grows with traffic and needs the purge job.

### Confirmation

- `OutboxPublisherIT`: publishing outside a transaction fails; a rollback removes both the state
  change and the event; a commit stores every envelope field.
- `OutboxDispatcherIT`: an event committed while no dispatcher runs is dispatched by an
  application started afterwards (REQ-REL-001); the secret is handed to the handler and then
  removed (REQ-SEC-005); a failing event stays pending without holding back the batch; two
  concurrent dispatchers hand each of 250 events over exactly once.
- `NotificationFanoutIT`: the same event handed over three times creates one delivery per
  recipient (REQ-FUNC-061).
- `DeliveryCancellationIT` and `NotificationCriteriaIT`: revoked, replaced and deleted-workspace
  deliveries end `CANCELLED` without their token, also when the cancellation is in the same batch
  as the invitation (REQ-FUNC-062, D-03 re-verification).
- `WorkerSchedulesIT`: no worker is scheduled on the test classpath.
- Re-confirmed later in the phase; a failure reopens this ADR: two application instances with
  1,000 events (REQ-REL-002, step 4.14) and the purge of published events with a controlled clock
  (REQ-FUNC-063, step 4.15).

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