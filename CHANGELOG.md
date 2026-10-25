# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/kaulinmindiola/warden/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/kaulinmindiola/warden/releases/tag/v0.1.0