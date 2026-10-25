# Warden

Workspace identity, access & notification platform — a Spring Boot modular monolith
with tenant isolation via PostgreSQL Row Level Security, refresh-token rotation with
reuse detection, and reliable e-mail delivery.

## Status

🚧 Early development. Milestone **M0 — buildable and de-risked** (`v0.1.0`) is complete:

- `./mvnw verify` runs formatting, version, architecture, unit and integration checks against
  real PostgreSQL 17 and Redis 8, locally and on every pull request.
- [POC-001](docs/poc/POC-001-library-compatibility.md): every planned library works on Spring Boot 4.1.
- [POC-002](docs/poc/POC-002-rls-pool.md): tenant context does not leak across pooled database connections.

No API endpoint is implemented yet. Next milestone: the identity core (registration, login,
sessions and refresh-token rotation).

## Documentation

- [Software Requirements Specification](docs/requirements/SRS-Warden.md)
- [Software Design Description](docs/design/SDD-Warden.md)
- [Architecture Decision Records](docs/adr/README.md)

## License

[Apache License 2.0](LICENSE)