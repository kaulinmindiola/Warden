# ADR-013: Persistence access strategy, migrations and database roles

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: REQ-INST-002, REQ-INST-003, REQ-CM-003, REQ-SEC-016; SDD V03, V06, V07

## Context and Problem Statement

Warden needs ORM productivity for its aggregates, but several guarantees depend on exact SQL:
the single-winner refresh rotation (`UPDATE … WHERE used_at IS NULL RETURNING`), worker claims
with `FOR UPDATE SKIP LOCKED`, and conditional revocations. The schema must be reproducible
from versioned files, and Row Level Security and the append-only audit log only work if the
role the application connects with does not own the tables. How should Warden access
PostgreSQL, evolve its schema and assign database roles?

## Decision Drivers

- Correctness under concurrency in a few critical statements.
- Productivity for ordinary aggregate persistence.
- Reproducible, reviewable schema changes, validated on every start.
- Row Level Security and append-only auditing enforced by PostgreSQL itself.

## Considered Options

- Access: JPA only; a SQL-first library only (jOOQ or MyBatis); plain JDBC only;
  **JPA for aggregates plus `JdbcClient` for critical statements**.
- Migrations: Hibernate `ddl-auto`; Liquibase; **Flyway versioned SQL**.
- Roles: one role for migrations and runtime; **an owner role and a separate runtime role**.

## Decision Outcome

- **Access.** Spring Data JPA with Hibernate for aggregates. `JdbcClient` for conditional
  updates, claims and any statement whose exact SQL is part of a guarantee. Both run in the
  same transaction and on the same connection.
- **Migrations.** Flyway, versioned SQL under `db/migration`, applied at application startup.
  Checksums are validated on every start; an applied migration is never edited, only
  followed by a new one. Hibernate never generates or alters the schema (`ddl-auto: none`).
- **Roles.**
    - `warden_owner` owns the database and every schema object; Flyway connects with it.
    - `warden_app` is the runtime role: it owns nothing, cannot create objects, cannot bypass
      RLS, and receives `SELECT, INSERT, UPDATE, DELETE` on the owner's tables through default
      privileges.
    - Append-only tables revoke `UPDATE` and `DELETE` from `warden_app`; their retention purge
      runs through a `SECURITY DEFINER` function owned by `warden_owner`.
- **One role script for every environment.** `ops/postgres/init/01-roles.sh` creates both
  roles from environment variables, so it contains no secret. Docker Compose runs it with
  local-only default passwords; the integration tests run the same script with random
  passwords.
- **Configuration.** Connection settings are bound from `WARDEN_DB_*` variables. Only the
  `local` profile has default passwords.

### Consequences

- Good: critical statements are explicit and reviewable; the rest of the persistence code
  stays concise.
- Good: RLS applies to the application, and a compromised runtime role can neither change
  the schema nor rewrite audit history.
- Good: the schema is reproducible and drift is detected at startup.
- Bad: two access styles. Rule: `JdbcClient` only where exact SQL is part of a guarantee;
  repository methods always take `workspaceId` explicitly.
- Bad: two sets of credentials to manage outside the local profile.
- Neutral (deviation from SDD V21): in Docker Compose, PostgreSQL and Redis publish their
  ports on `127.0.0.1` only, because the application runs on the host during development.
  The full reference environment can stop publishing them once the application runs in Compose.

### Confirmation

- `IntegrationTestSupportIT`: the application connects as `warden_app`, which is neither
  superuser nor `BYPASSRLS`; it cannot create a table (SQLSTATE `42501`); Flyway applied the
  baseline as `warden_owner`, which owns the schema history.
- POC-001 (`InfrastructureSmokeIT`): Flyway migrates as one role while the application
  connects as another.
- POC-002 (`RlsConnectionPoolLeakIT`): `JdbcClient` joins the JPA transaction's connection and
  sees its tenant context.
- Local run: Flyway reports "Successfully validated" and "No migration necessary" on the
  second start.

## Pros and Cons of the Options

### JPA only

- Good: one style.
- Bad: conditional `UPDATE … RETURNING` and `SKIP LOCKED` claims are awkward or opaque in JPQL.

### SQL-first library only

- Good: full SQL control with type safety.
- Bad: another dependency and code generation for aggregates that JPA handles well.

### Hibernate `ddl-auto`

- Good: no migration files.
- Bad: not reviewable, not reproducible, and cannot express policies, grants or functions.

### Liquibase

- Good: database-agnostic changelogs.
- Bad: XML/YAML abstraction over SQL that Warden would bypass for RLS and grants anyway.

### One database role

- Good: one credential.
- Bad: the runtime role would own the tables and bypass RLS, defeating SDD V07.