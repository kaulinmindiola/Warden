# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/kaulinmindiola/warden/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/kaulinmindiola/warden/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/kaulinmindiola/warden/releases/tag/v0.1.0