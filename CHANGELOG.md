# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.0] - 2026-10-30

Milestone M3: abuse protection and degradation. The sensitive endpoints are rate limited, Redis
and PostgreSQL failures have a defined, tested behaviour, and the hardening requirements hold.

### Added

- Rate limits with the SRS thresholds on Bucket4j token buckets refilled in full once per window:
  5 failed logins per e-mail in 15 minutes (checked before Argon2, so even the right password is
  rejected), 20 logins and 60 refreshes per IP per minute, 3 reset requests per e-mail and 10 per
  IP per hour, 3 verification resends per account per hour, and 50 invitations per workspace and
  100 per inviter per day. Rejections answer 429 `rate-limited` with `Retry-After`, never reveal
  whether an e-mail has an account and are counted in `warden_ratelimit_rejections_total`.
- Degraded modes: with Redis down, revocation checks go to PostgreSQL and the revocations of the
  last 630 seconds are rewritten before leaving degraded mode, with a reconciliation every 30
  seconds; rate limits use buckets local to the instance. Gauges `warden_revocation_degraded` and
  `warden_ratelimit_degraded`.
- PostgreSQL unavailable: connections time out after 2 seconds and every request that needs the
  database answers 503 `service-unavailable` with `Retry-After: 5`, including the token check when
  Redis is down too; readiness depends only on PostgreSQL and the application state.
- Audit retention: `purge_audit_log`, the only way to delete audit records, never deletes one
  younger than 365 days; a daily job runs it.
- The `prod-like` profile: TLS-only connector, `Strict-Transport-Security` for one year, and
  mandatory keystore and keys.
- Evidence tests: fail-closed authorization with each fault injected on every workspace endpoint,
  one complete audit record without secrets per audited action, a scan of API responses for
  secrets with a gate on every read endpoint, and outage tests with the Redis and PostgreSQL
  containers frozen.
- Threat model v2 covering abuse protection, degraded modes, audit retention and transport.
- ADR-007 and ADR-014 accepted.

### Changed

- Redis commands time out after 200 ms instead of 60 s.
- The workspace authorization interceptor answers 503 with `Retry-After: 5` when memberships cannot
  be read.
- Integration test pools keep one idle connection, and the test PostgreSQL allows 300 connections.

### Known limitations

- Audit records for template changes and dead-letter retries (REQ-SEC-015), templates without
  execution (REQ-SEC-013) and rendered notification content in the response scan (REQ-SEC-014)
  arrive with the notification module in M4; they are recorded as exceptions to the M3 exit
  criteria.
- The REQ-SEC-010 timing analysis is pending; only its mechanism is in place.

## [0.3.0] - 2026-10-28

Milestone M2: multi-tenancy and RBAC. Workspaces, memberships, roles, invitations and notes work
through the API, and tenant isolation holds in three layers that the build itself verifies.

### Added

- Workspaces: creation by users with a verified e-mail, listing with the caller's role, reading,
  and logical, irreversible deletion by an OWNER that revokes pending invitations.
- Roles VIEWER < MEMBER < ADMIN < OWNER resolved from the current membership on every request,
  never from the token: non-members, foreign and deleted workspaces get a 404 identical to a
  random id, and an insufficient role gets 403.
- Member management: listing, role changes with authority rules, removal and leaving, with the
  last OWNER protected under concurrency by a workspace row lock.
- Invitations: creation with a sealed, single-use, 7-day link; re-invitation that replaces the
  previous link; listing without tokens; revocation; and acceptance only by the verified invited
  address, without consuming the token when another account tries it.
- Notes as the reference workspace resource, with optimistic versioning and 409 on stale edits.
- Tenant isolation in three layers: the workspace authorization interceptor, the Hibernate tenant
  filter and forced PostgreSQL Row Level Security with transaction-local context, plus a
  token-holder policy for accepting invitations and column-level privileges on invitations.
- Build gates: every workspace endpoint must be in the isolation registry (R7) and declare its
  minimum role (FF-05); the isolation suite (foreign and deleted workspaces) and the role matrix
  run against every registered endpoint.
- Evidence tests: Row Level Security proven with raw JDBC as the runtime role; 50 repetitions of
  concurrent note edits and of concurrent demotions of two OWNERs; the R7 gate shown failing in
  CI on a disposable pull request; the plaintext-secret scan now includes invitation tokens.
- Workspace events in the outbox (`MemberInvited`, `InvitationRevoked`, `InvitationAccepted`,
  `MemberRoleChanged`, `WorkspaceDeleted`) and audit records for every workspace action.
- Threat model v1 covering tenancy and invitations.
- ADR-004 and ADR-005 accepted.

## [0.2.0] - 2026-10-26

Milestone M1: the identity core. Accounts, sessions and tokens work end to end through the API,
and none of the flows reveals whether an e-mail has an account.

### Added

- Shared kernel: RFC 9457 problem details with a fixed error catalogue, the `/api/v1` prefix,
  pagination, UUIDv7 identifiers, UTC timestamps, a 64 KiB request limit, structured JSON logs
  and deny-by-default security.
- Key material for ES256 signing, AES-256-GCM sealing and HMAC, with ephemeral keys only in the
  local profile.
- Secure 256-bit tokens, SHA-256 token hashes, an append-only audit log and the write side of the
  transactional outbox.
- Registration with e-mail normalization, a password policy with a common-password list and
  bounded Argon2id hashing; identical responses for new and existing e-mails.
- E-mail verification with single-use, 24-hour links, and resending them.
- Login with ES256 access tokens and server-side sessions; strict token validation.
- Session listing, termination of one session and logout, rejected on the next request through
  a Redis denylist with a PostgreSQL fallback.
- Refresh-token rotation with a single conditional update, reuse detection that revokes the
  session with exactly one alert, and expiry without alerts.
- Password reset with single-use, 30-minute links; a reset revokes every session and verifies
  the e-mail.
- A configurable minimum response time for registration and password reset requests.
- Build gate: 80 % line coverage of the domain and application layers.
- Evidence tests: 50 repetitions of four concurrent refreshes with a single winner, and a scan
  of every database column for plaintext secrets.
- Threat model v0 for authentication and sessions.
- ADR-002, ADR-003, ADR-006, ADR-010, ADR-011, ADR-012, ADR-017 and ADR-019 accepted; ADR-008
  proposed.

## [0.1.0] - 2026-10-25

Milestone M0: the project builds reproducibly and its two riskiest design assumptions are proven.
No API endpoint is implemented yet.

### Added

- Spring Boot 4.1.2 application skeleton with five modules (`iam`, `workspace`, `notes`,
  `notification`, `shared`) whose boundaries are verified by Spring Modulith and ArchUnit.
- Build gates in `./mvnw verify`: pinned JDK, Maven and dependency versions, formatting,
  separate unit and integration test phases, and a coverage report.
- Continuous integration on every pull request and every push to `main`; `main` requires a
  passing `verify` check.
- Local PostgreSQL 17 and Redis 8 with Docker Compose, and the database role split: an owner
  role for migrations and a runtime role subject to Row Level Security.
- Flyway baseline migration applied at startup as the schema owner.
- Shared integration test infrastructure on Testcontainers that connects as the runtime role,
  with a controllable test clock.
- POC-001: compatibility of every planned library with Spring Boot 4.1.
- POC-002: tenant context does not leak across pooled database connections.
- ADR-001, ADR-013 and ADR-020 accepted; ADR-005 proposed.

[Unreleased]: https://github.com/kaulinmindiola/warden/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/kaulinmindiola/warden/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/kaulinmindiola/warden/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/kaulinmindiola/warden/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/kaulinmindiola/warden/releases/tag/v0.1.0