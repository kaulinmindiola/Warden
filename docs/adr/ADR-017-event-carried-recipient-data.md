# ADR-017: Notification data ownership and event-carried recipient data

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: REQ-MAINT-002, REQ-FUNC-049, REQ-FUNC-050, REQ-FUNC-051, REQ-FUNC-063, RISK-001, DEC-007, DEC-039; SDD V02, V04, V10, V13; ADR-008, ADR-011

## Context and Problem Statement

The `notification` module must not depend on `iam`, `workspace` or `notes` (REQ-MAINT-002,
SDD V04 rules R3 and R4). Yet, to send a message, it needs the recipient's e-mail address and a
language. The language can also be changed by the user in the notification preferences
(REQ-FUNC-049, REQ-FUNC-051).

Which module owns each piece of recipient data, and how does `notification` get it without
querying other modules?

## Decision Drivers

- `notification` stays independent and testable on its own (REQ-MAINT-002).
- A delivery must be possible even if the producing module is unavailable or has changed since
  the event (at-least-once dispatch, ADR-008).
- Recipients without an account (invitations) must be supported.
- Personal data is kept only as long as needed.

## Considered Options

1. `notification` queries `iam` (for example a `UserDirectory`) when it sends.
2. `notification` keeps its own replica of users, fed by account events.
3. Every event carries the recipient data it needs; `notification` owns only preferences and
   locale overrides.
4. `notification` reads the user tables of `iam` directly.

## Decision Outcome

Chosen option: **3, event-carried recipient data**.

- Events carry `Recipient = {userId?, email, fallbackLocale}` (SDD V10). The record lives in
  `shared.outbox` because `iam` and `workspace` both produce it.
- `userId` is null when the address has no account (invitations).
- `iam` events use the holder's registration locale as `fallbackLocale`. A notice about an
  attempt on an existing account goes to the holder in the holder's language, not in the
  language of whoever made the attempt.
- Multi-recipient events carry every recipient captured at event time; for example,
  `workspace.MemberRoleChanged` carries the affected member and the owners.
- `notification` owns `notification_preference` and `recipient_setting`. The locale is resolved
  as `recipient_setting.locale`, then `fallbackLocale`, then `es` (REQ-FUNC-051).
- Workspace-scoped notification endpoints check membership through the `shared` port
  `MembershipLookup`, implemented by `workspace` (phase 2), not by calling `workspace` directly.

### Consequences

- Good: `notification` never imports another domain module, and its tests need no other module.
- Good: a delivery has everything it needs from the moment the event commits, even across
  restarts.
- Bad: the e-mail address is copied into `outbox_event` and `notification_delivery`. The outbox
  copy is purged 7 days after publication (REQ-FUNC-063), and `Recipient.toString()` never
  prints the address.
- Bad: data is a snapshot at event time; a delivery retried later uses the address and owners
  captured when the event happened.
- Neutral: events are larger, which is acceptable for the volumes of the reference
  environment.

### Confirmation

- `RegistrationIT#aNewEmailCreatesAnUnverifiedAccountAndAVerificationEvent`:
  `iam.EmailVerificationRequested` carries the recipient's `userId`, normalized e-mail and
  `fallbackLocale`.
- `RegistrationIT#anExistingEmailGetsAnIdenticalResponse…`: the notice uses the holder's
  locale.
- `ArchitectureTest`: module boundaries.
- Planned: the ArchUnit rule R3 (`notification` imports only `shared`) and locale resolution in
  phase 4.

## Pros and Cons of the Options

### Query `iam` at send time

- Good: always-current data; nothing duplicated.
- Bad: violates R3 and couples delivery availability to `iam`.

### Replicated user data in `notification`

- Good: local reads, current data.
- Bad: more events, eventual-consistency gaps and a second copy of personal data to keep in
  sync.

### Event-carried recipient data

- Good: independence and self-contained deliveries.
- Bad: snapshot semantics and copies of the address that must be purged.

### Direct reads of `iam` tables

- Good: no extra code.
- Bad: hidden coupling at the database level; breaks module boundaries.