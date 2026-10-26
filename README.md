# Warden

Workspace identity, access & notification platform — a Spring Boot modular monolith
with tenant isolation via PostgreSQL Row Level Security, refresh-token rotation with
reuse detection, and reliable e-mail delivery.

## Status

🚧 Early development. Milestone **M1 — identity core** (`v0.2.0`) is complete:

- Registration, e-mail verification, login, sessions, refresh-token rotation with reuse
  detection, logout, session termination and password reset, all without revealing whether an
  e-mail has an account.
- Short-lived ES256 access tokens; revoked sessions are rejected on the very next request
  through a Redis denylist, with PostgreSQL as the fallback.
- Single-use tokens stored only as hashes; link tokens travel in events only encrypted;
  append-only audit log and transactional outbox.
- `./mvnw verify` runs formatting, version, architecture, unit and integration checks against
  real PostgreSQL 17 and Redis 8, and fails below 80 % line coverage of the domain and
  application layers.

Workspaces, roles and notes come next (M2). Notifications are recorded in the outbox but not
yet delivered by e-mail (M4).

## Run locally

Prerequisites: JDK 21 (Temurin), Docker Desktop (or Docker Engine with Compose) and Git.

1. Start PostgreSQL and Redis:

   ```bash
   docker compose up -d
   ```

2. Start the application with the `local` profile, which uses the local-only credentials of
   `compose.yaml` and generates ephemeral keys:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

   On Windows PowerShell: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"`.

3. The API listens on `http://localhost:8080/api/v1`; health checks are on the management port,
   `http://localhost:8081/actuator/health`.

4. Try it:

   ```bash
   curl -i -H "Content-Type: application/json" \
     -d '{"email":"ada@example.com","password":"correct horse battery staple"}' \
     http://localhost:8080/api/v1/auth/register
   curl -s -H "Content-Type: application/json" \
     -d '{"email":"ada@example.com","password":"correct horse battery staple"}' \
     http://localhost:8080/api/v1/auth/login
   ```

Ephemeral keys do not survive a restart: tokens issued before it become invalid. To keep them,
and to change ports or passwords, see [`.env.example`](.env.example).

Run the full build, including the integration tests on Testcontainers (Docker must be running):

```bash
./mvnw verify
```

### API

| Method | Path | Access |
|--------|------|--------|
| POST | `/api/v1/auth/register` | Public |
| POST | `/api/v1/auth/email/verify` | Public |
| POST | `/api/v1/auth/email/verify/resend` | Authenticated |
| POST | `/api/v1/auth/login` | Public |
| POST | `/api/v1/auth/refresh` | Public (refresh token in the body) |
| POST | `/api/v1/auth/logout` | Authenticated |
| GET | `/api/v1/auth/sessions` | Authenticated |
| DELETE | `/api/v1/auth/sessions/{sessionId}` | Authenticated |
| POST | `/api/v1/auth/password/forgot` | Public |
| POST | `/api/v1/auth/password/reset` | Public |

Errors follow RFC 9457; the types are listed in [problem-types.md](docs/api/problem-types.md).

## Documentation

- [Software Requirements Specification](docs/requirements/SRS-Warden.md)
- [Software Design Description](docs/design/SDD-Warden.md)
- [Architecture Decision Records](docs/adr/README.md)
- [Threat model](docs/security/threat-model.md)
- [Changelog](CHANGELOG.md)

## License

[Apache License 2.0](LICENSE)