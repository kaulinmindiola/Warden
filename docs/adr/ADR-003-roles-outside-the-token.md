# ADR-003: Roles resolved per request, outside the access token

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: REQ-FUNC-022, REQ-INT-004, REQ-PERF-003, BR-004; SDD V05, V08, V11, V12; ADR-004, ADR-005

## Context and Problem Statement

A user can belong to several workspaces with a different role in each (VIEWER, MEMBER, ADMIN,
OWNER). A role change or a removal must take effect on the affected user's next request
(BR-004, REQ-FUNC-022). Where are roles evaluated: in claims inside the access token, or by
looking up the membership on every request?

## Decision Drivers

- Freshness: a demoted or removed member must lose access immediately, not when the token
  expires.
- Token minimization: the token must not contain roles, e-mail or workspace data (REQ-INT-004).
- Bounded cost of authorization per request (REQ-PERF-003).

## Considered Options

1. Roles as claims in the access token.
2. Roles looked up per request, uncached.
3. Roles looked up per request, cached with a short TTL.

## Decision Outcome

Chosen option: **2, uncached membership lookup on every workspace request**.

- The access token identifies only the user and the session (`sub`, `sid`). The resulting
  `CurrentUserAuthentication` has no authorities.
- From phase 2, the workspace authorization interceptor resolves `(workspaceId, userId) → role`
  through the `shared` port `MembershipLookup` on every request under `/workspaces/{id}/**`: no
  membership → 404, insufficient role → 403 (ADR-004).
- No cache: correctness of BR-004 outweighs one indexed primary-key lookup per request.

### Consequences

- Good: role changes and removals apply on the next request with zero tolerance.
- Good: tokens stay small, and a role change never requires reissuing tokens.
- Bad: one database lookup per workspace request; the primary-key index keeps it within the
  REQ-PERF-003 budget, to be confirmed by the load tests.
- Neutral: a cache can be added later only if measurements require it, and would need its own
  invalidation decision.

### Confirmation

- `JwtIssuerTest`, `LoginIT`: tokens contain exactly `iss`, `sub`, `sid`, `jti`, `iat` and
  `exp`.
- `CurrentUserAuthenticationConverterTest`: the authentication has no authorities.
- Planned: the interceptor tests of phase 2 (a role change applies on the next request).

## Pros and Cons of the Options

### Roles in the token

- Good: no lookup per request.
- Bad: stale for up to the token lifetime, violating BR-004; leaks workspace structure into the
  token; a user in many workspaces gets a large token.

### Uncached lookup per request

- Good: always correct; simple.
- Bad: a database read per workspace request.

### Cached lookup

- Good: fewer reads.
- Bad: needs cross-instance invalidation to keep BR-004; adds a failure mode for a small gain.