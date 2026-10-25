# POC-002 — Row Level Security with a connection pool

| Field | Value |
|-------|-------|
| Status | Completed — no leak; design confirmed |
| Date | 2026-10-25 |
| Requirement | REQ-POC-002 |
| Risk addressed | DRISK-02: tenant context leaking across pooled connections |
| Design under test | SDD V07, layer 3: transaction-local GUC and a `NULLIF` policy |
| Platform | PostgreSQL 17, HikariCP (managed by Spring Boot 4.1.2), Java 21 |
| Evidence | `RlsConnectionPoolLeakIT` (5 tests), kept as a permanent regression test |

## Question

Warden carries the tenant of a request to PostgreSQL with
`set_config('app.workspace_id', :id, true)` inside the transaction, and Row Level Security
policies read it with `NULLIF(current_setting('app.workspace_id', true), '')::uuid`.
Connections are pooled, so the same physical connection serves many requests in sequence.
Can the tenant of one transaction ever be visible to the next transaction on that connection?

## Setup

- A throwaway table created by `warden_owner`, with `ENABLE` and `FORCE ROW LEVEL SECURITY`
  and the policy of SDD V07 for both `USING` and `WITH CHECK`.
- Rows seeded by the superuser: 3 for tenant A, 2 for tenant B.
- Every assertion about isolation runs as `warden_app`, which owns nothing and cannot bypass
  RLS. The superuser is used only to seed data and as a negative control.

## Results

| Test | Scenario | Asserted outcome | Result |
|------|----------|------------------|--------|
| `alternatingTenantsOnAPoolOfTwoNeverReadEachOthersRows` | 1 000 transactions alternating A and B, from 4 threads, on a Hikari pool of 2 connections | 0 cross reads; every transaction sees exactly its tenant's row count; at least one physical connection (same `pg_backend_pid()`) served both tenants | Pass |
| `aTransactionWithoutContextSeesNoRowsEvenOnAReusedConnection` | Pool of 1 connection: a tenant A transaction, then a transaction without context | Same backend in both; `current_setting('app.workspace_id', true)` returns `''`; 0 rows visible | Pass |
| `jdbcClientInsideAJpaTransactionSharesTheConnectionAndTheContext` | Context set through a Hibernate native query, rows read through `JdbcClient`, inside one `JpaTransactionManager` transaction | Same backend; `JdbcClient` sees exactly tenant A's rows | Pass |
| `onlyTheSuperuserBypassesTheForcedPolicy` | Counting rows without context | Superuser sees 5; table owner sees 0 | Pass |
| `withCheckRejectsWritingARowOfAnotherTenant` | Inside tenant A's context, insert a row for tenant B | Rejected with SQLSTATE `42501`; no row written | Pass |

The proof of connection reuse in the first two tests matters: a pass without it could mean
that each tenant happened to use its own connection, which would prove nothing.

## Findings

1. **No leak.** A transaction-local setting ends with its transaction; the next transaction on
   the same pooled connection does not inherit it.
2. **`NULLIF` is required.** After a transaction-local setting ends, the parameter reads as an
   empty string on that connection, not as `NULL`. Without `NULLIF`, the cast `''::uuid`
   raises an error instead of returning no rows.
3. **`FORCE ROW LEVEL SECURITY` is required.** Without it the table owner bypasses the policy.
   With it, even `warden_owner` sees nothing without a context.
4. **Only a superuser bypasses the forced policy.** Tests that assert isolation must never
   connect as one, or they pass falsely.
5. **One connection per transaction.** `JdbcClient` joins the JDBC connection of a
   `JpaTransactionManager` transaction and sees its context, so JPA and SQL can be mixed in
   the same tenant transaction.
6. **`WITH CHECK` protects writes.** A row of another tenant cannot be inserted inside a
   tenant context.

## Design rules confirmed for the implementation

- The tenant context is set only with `is_local = true`, inside a transaction opened by the
  tenant-transaction helper; never at session level. Outside a transaction block a local
  setting would last only for its own statement.
- Every tenant-scoped table uses `ENABLE` and `FORCE ROW LEVEL SECURITY` and the `NULLIF`
  policy shape, for both `USING` and `WITH CHECK`.
- The runtime role owns no table; migrations run as the owner.

## Not covered by this POC

Layers 1 and 2 of SDD V07 (per-request membership resolution and the application-level
Hibernate filter) and the system context used by background workers. They are verified when
the tenancy implementation exists.

## How to reproduce

```bash
./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RlsConnectionPoolLeakIT
```

Docker must be running.