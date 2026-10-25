# ADR-005: Tenant isolation with an application filter and Row Level Security

- Status: proposed
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: G-01, DEC-016, REQ-SEC-011, REQ-SEC-012, REQ-MAINT-003, REQ-POC-002; SDD V06, V07, V12

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

Proposed option: **5, three layers** as described in SDD V07.

| Layer | Mechanism | Failure it covers |
|-------|-----------|-------------------|
| L1 — access resolution | Interceptor resolves the caller's membership of the route's workspace on every request, without caching | Non-member, deleted workspace, insufficient role |
| L2 — application filter | Tenant transactions enable a Hibernate filter; repository methods take `workspaceId` explicitly | A query that loads another workspace's row by id |
| L3 — Row Level Security | The same transaction runs `set_config('app.workspace_id', :id, true)`; policies use `NULLIF(current_setting('app.workspace_id', true), '')::uuid`; tables use `FORCE ROW LEVEL SECURITY`; the runtime role owns nothing | A forgotten filter or a native query |

Status stays `proposed` until layers 1 and 2 exist and the isolation suite and the raw JDBC
RLS tests run against real tenant tables.

### Consequences

- Good: a leak requires three independent failures.
- Good: one schema and one database; no per-tenant migrations or connection routing.
- Bad: every tenant-scoped table needs a policy, `FORCE`, and tests; a table created without
  them is unprotected at layer 3.
- Bad: every tenant operation must run inside a transaction opened by the tenant-transaction
  helper; a transaction-local setting outside a transaction block lasts only one statement.
- Bad: background workers need an explicit, narrowly restricted system context.

### Confirmation

Already confirmed (POC-002, `RlsConnectionPoolLeakIT`, layer 3):

- 1 000 transactions alternating two tenants on a two-connection pool: zero cross reads, with
  connection reuse between tenants proven.
- A transaction without context on a reused connection sees no rows; the parameter reads as
  `''`, which is why the policy uses `NULLIF`.
- `JdbcClient` and JPA share the same connection and context in one transaction.
- The owner sees nothing without context because of `FORCE`; only a superuser bypasses it.
- `WITH CHECK` rejects writing another tenant's row (SQLSTATE `42501`).

Pending before acceptance:

- An isolation suite covering every `/workspaces/{id}/**` endpoint, enforced by a build-time
  registry (REQ-MAINT-003).
- Raw JDBC RLS tests on the real tenant tables without the application filter (REQ-SEC-012).

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