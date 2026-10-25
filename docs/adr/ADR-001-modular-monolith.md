# ADR-001: Modular monolith instead of microservices

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: CON-005, DEC-052, REQ-MAINT-001, REQ-MAINT-002; SDD V02, V03, V04, V20

## Context and Problem Statement

Warden has four business capabilities (identity, workspaces, notes, notifications) and a
technical kernel. They must be developed, tested and operated by one person at zero cost,
yet keep boundaries strict enough that a reviewer can trust them: `notification` must not
depend on the other modules (REQ-MAINT-002), and the module graph must stay acyclic
(REQ-MAINT-001). How should Warden be split into deployable units so that its boundaries are
enforceable?

## Decision Drivers

- Boundaries verifiable by an automated check, not by convention.
- Transactions that span a state change and its outbox event without a distributed protocol.
- One-command reproducibility and zero infrastructure cost (CON-007, CON-009).
- Effort proportional to a single developer.

## Considered Options

1. Microservices (one service per capability).
2. A layered monolith without module boundaries.
3. A modular monolith with boundaries verified in the build.

## Decision Outcome

Chosen option: **3, a modular monolith**. One Spring Boot deployable with five application
modules (`iam`, `workspace`, `notes`, `notification`, `shared`) as top-level packages under
`io.github.kaulinmindiola.warden`. Allowed dependencies follow SDD V04:
`workspace → iam`, every domain module → `shared`, `shared` → none, and `notification` →
`shared` only. Information reaches `notification` only through outbox events.

Boundaries are enforced in `./mvnw verify`:

- Spring Modulith `ApplicationModules.verify()` checks cycles and access to module internals.
- ArchUnit rules cover what Modulith does not express, starting with "`shared` depends on no
  domain module".

### Consequences

- Good: one process, one database and local ACID transactions; the transactional outbox
  needs no distributed coordination.
- Good: a boundary violation fails the build, which keeps the architecture honest as code grows.
- Good: the system runs with one command and costs nothing to operate.
- Bad: modules cannot be scaled or deployed independently; the whole deployable scales together.
- Bad: one shared PostgreSQL database; isolation between modules relies on discipline enforced
  by tests (references by id only, no cross-module JPA associations).
- Neutral: if a module had to be extracted, `notification` is the first candidate: it depends
  only on `shared` and already receives its input as events.

### Confirmation

- `ArchitectureTest#modulesRespectTheirBoundaries` (Spring Modulith 2.1.2) detects the five
  modules and verifies them on every build.
- `ArchitectureTest#sharedKernelDependsOnNoDomainModule` (ArchUnit 1.5.1).
- POC-001 confirmed both libraries on Spring Boot 4.1.2.
- Each module declares its allowed dependencies as it gains code, and the rule
  "`notification` imports only `shared`" is added before that module's first class.

## Pros and Cons of the Options

### Microservices

- Good: independent deployment and scaling.
- Bad: network boundaries, distributed transactions or sagas, service discovery and one
  pipeline per service, with no requirement that benefits from them.
- Bad: contradicts CON-005 and multiplies operational cost.

### Layered monolith without module boundaries

- Good: simplest to start.
- Bad: nothing prevents `notification` from querying `iam` directly; boundaries erode silently.

### Modular monolith with verified boundaries

- Good: the boundary benefits of services at the cost of a monolith.
- Bad: requires architecture tests and discipline about what goes into `shared` (SDD V02
  admission rule: used by at least two modules and free of business rules).