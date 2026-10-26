# ADR-006: Atomic refresh-token rotation and reuse detection

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: REQ-FUNC-011 to REQ-FUNC-014, REQ-FUNC-018, BR-005, RISK-008; SDD V11, V14-A1, V15, V16; ADR-002, ADR-007

## Context and Problem Statement

Every refresh exchanges the presented refresh token for a new one in the same session
(REQ-FUNC-011). A refresh token that is presented again after being used is a sign of theft: the
whole session must be revoked and its holder warned, exactly once (REQ-FUNC-012, BR-005). When
several requests present the same token at the same time, exactly one may succeed, and the
session must end revoked (REQ-FUNC-013). Expiry must never raise an alert (REQ-FUNC-014). How is
rotation made atomic under concurrency, and how is reuse told apart from expiry?

## Decision Drivers

- Exactly one winner per refresh token, whatever the concurrency and the number of instances.
- A single alert per session, even under concurrent reuse.
- No extra infrastructure, and no lock held across the network.
- Indistinguishable responses for every rejection.

## Considered Options

1. A conditional `UPDATE … WHERE used_at IS NULL AND expires_at > now RETURNING session_id`.
2. `SELECT … FOR UPDATE`, then a check in Java, then an update.
3. An optimistic version column on the refresh token.
4. A distributed lock in Redis around the rotation.
5. Stateless refresh tokens (signed JWTs) with no server-side record.

## Decision Outcome

Chosen option: **1, a single conditional update**, inside one transaction (SDD V14-A1).

- **Rotation:** `UPDATE refresh_token SET used_at = now WHERE token_hash = ? AND used_at IS NULL
  AND expires_at > now RETURNING session_id`. Concurrent callers block on the row lock; after the
  winner commits, PostgreSQL re-evaluates the condition and returns no row for the others. The
  winner checks that the session is active, stores the hash of a new token expiring at `min(now
    + 7 days, absolute expiry)` and updates the session's last use. An inactive session rolls the
      transaction back, leaving the token unused.
- **Reuse:** when the update returns no row, the token exists, has `used_at` set and its session
  is active, the session is revoked with `UPDATE … WHERE revoked_at IS NULL`. Only the caller
  whose revocation changed the row publishes `iam.RefreshTokenReuseDetected` and audits the
  reuse, so there is a single alert. The transaction returns an outcome instead of throwing, so
  the revocation commits while the response is 401. After the commit, the session is marked in
  the revocation store, so its access tokens fail on the next request (ADR-007).
- **Expiry:** an unknown token, or one that expired unused, is rejected without any alert; an
  expired session is inactive and is not revoked again.
- Every rejection answers the same 401 `invalid-refresh-token`.

A request that loses a concurrent race sees a used token on an active session and therefore
revokes it. This is intended: REQ-FUNC-013 requires one winner and a revoked session. Clients
must not retry a refresh with the same token after a timeout (RISK-008).

### Consequences

- Good: atomicity comes from one statement and PostgreSQL's row locking; it works unchanged with
  several instances.
- Good: the single-alert guarantee comes from a second conditional statement, not from timing.
- Bad: a legitimate client that retries the same refresh after a network timeout loses its
  session. This is documented for API consumers as part of the contract.
- Neutral: one indexed lookup by token hash per rejected refresh.

### Confirmation

- `ConcurrentRefreshIT`: 50 repetitions of 4 concurrent HTTP refreshes with the same token. In
  every repetition exactly one request answers 200, the other three answer 401, the session ends
  revoked with reason `REUSE` and exactly one reuse event exists.
- `RefreshIT`: rotation keeps the `sid`, marks the old token used, sets the 7-day expiry and the
  session's last use; a used, unknown or malformed token gets the same 401; the token of a logged
  out session is rejected and left unused.
- `RefreshReuseAndExpiryIT`: reuse revokes the session, makes both the rotated refresh token and
  every access token fail, with exactly one event and one audit record; 7 days without use and a
  session refreshed daily for 30 days both end in 401 without any alert.

## Pros and Cons of the Options

### Conditional update

- Good: one round trip, no explicit lock management, correct under any concurrency.
- Bad: the reuse branch needs a follow-up lookup to tell reuse from expiry.

### SELECT FOR UPDATE

- Good: explicit and familiar.
- Bad: two statements and a lock held while Java decides; easy to get wrong.

### Optimistic version column

- Good: no blocking.
- Bad: losers fail with a conflict that must be mapped and retried; the extra column adds
  nothing over the `used_at IS NULL` condition.

### Distributed lock in Redis

- Good: works across stores.
- Bad: adds a dependency on Redis for correctness, plus lock-expiry edge cases; PostgreSQL
  already provides the guarantee.

### Stateless refresh tokens

- Good: no storage.
- Bad: cannot detect reuse or revoke a single session; contradicts REQ-FUNC-012 and
  REQ-FUNC-013.