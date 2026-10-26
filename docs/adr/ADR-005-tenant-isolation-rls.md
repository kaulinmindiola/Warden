# ADR-005: Tenant isolation with an application filter and Row Level Security

- Status: accepted
- Date: 2026-10-26
- Deciders: Kaulin Mindiola
- Related: G-01, DEC-016, MUC-01, RISK-009, REQ-FUNC-022, REQ-FUNC-023, REQ-FUNC-025, REQ-SEC-011, REQ-SEC-012, REQ-MAINT-003, REQ-POC-002; SDD V06, V07, V12; ADR-003, ADR-004

## Context and Problem Statement

A valid token must never cross workspaces (G-01). Every workspace-scoped resource shares the
same tables, distinguished by `workspace_id`. A single forgotten filter, or a native query
written without one, would leak another workspace's data. How can isolation hold even when
application code is wrong, without the operational cost of separate schemas or databases?

## Decision Drivers

- Isolation must survive one programming mistake (defense in depth).
- The effective tenant is always the workspace id in the route, never one from a request body.
- Pooled connections must not carry a tenant from one request to the next.
- One database and one schema, so the reference environment stays a single Compose stack.

## Considered Options

1. Application-level filtering only.
2. Row Level Security only.
3. Schema per tenant.
4. Database per tenant.
5. Three layers: per-request membership resolution, application filter, and Row Level Security.

## Decision Outcome

Chosen option: **5, three layers**, as described in SDD V07.

| Layer | Mechanism | Failure it covers |
|-------|-----------|-------------------|
| L1 — access resolution | `WorkspaceAuthorizationInterceptor` resolves the caller's membership of the route's workspace through `MembershipLookup` on every request, without caching (ADR-003, ADR-004) | Non-member, deleted workspace, insufficient role |
| L2 — application filter | `TenantTransactions.inWorkspace` opens the transaction and enables the Hibernate filter `tenant` on tenant-scoped entities; repository methods take `workspaceId` explicitly | A query that loads another workspace's row by id |
| L3 — Row Level Security | The same transaction runs `set_config('app.workspace_id', :id, true)`; policies use `NULLIF(current_setting('app.workspace_id', true), '')::uuid`; tables use `FORCE ROW LEVEL SECURITY`; the runtime role owns nothing | A forgotten filter or a native query |

Implementation rules:

- The route variable of every workspace endpoint is `{workspaceId}`; workspace ids in request
  bodies are never used as the tenant.
- `TenantTransactions` refuses to run inside another transaction, so a tenant setting can never
  leak into an outer transaction.
- `TenantTransactions.forUser` sets `app.user_id` for operations across the caller's own
  memberships; `workspace_member` uses the policy `member_visibility` (current tenant or own rows).
- Policies of writable tenant tables have `WITH CHECK`, so a row cannot be written into another
  workspace (for example `note_tenant`).
- `TenantTransactions.system` sets `app.system_context` for background workers; its policies and
  the rule that restricts its callers (R5) arrive with the notification workers in phase 4.

### Consequences

- Good: a leak requires three independent failures.
- Good: one schema and one database; no per-tenant migrations or connection routing.
- Bad: every tenant-scoped table needs a policy, `FORCE`, and tests; a table created without
  them is unprotected at layer 3.
- Bad: every tenant operation must run inside a transaction opened by the tenant-transaction
  helper; a transaction-local setting outside a transaction block lasts only one statement.
- Bad: background workers need an explicit, narrowly restricted system context.

### Confirmation

Layer 3 on its own:

- POC-002 (`RlsConnectionPoolLeakIT`): 1 000 transactions alternating two tenants on a
  two-connection pool, with zero cross reads and connection reuse proven; a reused connection
  without context sees no rows, which is why policies use `NULLIF`.
- `NoteRowLevelSecurityIT`: with raw JDBC as the application role and only `app.workspace_id`
  set, rows of another workspace cannot be read, updated, deleted or inserted (`WITH CHECK`);
  without context no row is visible or writable.
- `WorkspaceIT`: memberships are invisible to the application role outside their context and
  visible only to their own user.

Layers 2 and 3 when layer 1 lets the request through:

- `NoteCrossWorkspaceIT`: a member of W and X cannot read, edit or delete a note of X through
  W's route; the response equals that for an unknown note and the note of X stays intact.

Layer 1 and its coverage:

- `IsolationSuiteIT`: for every registered workspace endpoint, a non-member and the OWNER of
  another workspace get the same 404 as a random id.
- `WorkspaceEndpointRulesIT`: the build fails if a workspace handler is not in the isolation
  registry (R7), uses another route variable, or lacks `@RequiresWorkspaceRole` (FF-05).
- `TenantTransactionsIT`: each tenant transaction sets only its own context, enables the filter
  only in a workspace, leaves nothing behind and refuses to nest.

Pending, without blocking acceptance: the system context and rule R5 (phase 4).

## Pros and Cons of the Options

### Application-level filtering only

- Good: simple; no database features involved.
- Bad: one forgotten filter is a leak; nothing catches native queries.

### Row Level Security only

- Good: enforced by the database regardless of code.
- Bad: errors surface as empty results with no clear 404/403 semantics, and a wrong context
  setting is the only line of defense.

### Schema per tenant

- Good: strong separation.
- Bad: migrations multiply with tenants; connection routing and pool sizing per schema.

### Database per tenant

- Good: strongest separation.
- Bad: operational cost far beyond the project's constraints (CON-007, CON-009).

### Three layers

- Good: defense in depth with one schema.
- Bad: more moving parts, each of which needs its own tests.