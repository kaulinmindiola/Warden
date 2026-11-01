# Warden

Workspace identity, access & notification platform — a Spring Boot modular monolith
with tenant isolation via PostgreSQL Row Level Security, refresh-token rotation with
reuse detection, and reliable e-mail delivery.

## Status

🚧 Early development. Milestone **M4 — notifications** (`v0.5.0`) is complete:

- Identity core (M1): registration, e-mail verification, login, sessions, refresh-token rotation
  with reuse detection, logout, session termination and password reset, all without revealing
  whether an e-mail has an account.
- Workspaces with the roles VIEWER, MEMBER, ADMIN and OWNER, member management with last-OWNER
  protection, invitations by e-mail with single-use links, and notes as the reference resource.
- Tenant isolation in three layers: authorization against the current membership on every
  request, a Hibernate tenant filter and forced PostgreSQL Row Level Security. A non-member cannot
  tell a foreign or deleted workspace from a non-existent one.
- Abuse protection (M3): rate limits on login, refresh, password recovery, verification resend
  and invitations, enforced with buckets local to the instance when Redis fails. A Redis outage
  keeps revocation working through PostgreSQL; a PostgreSQL outage answers 503 and never grants
  access. Audit records are complete and kept 365 days, no response carries a secret, and the
  `prod-like` profile serves HTTPS only with HSTS.
- Notifications (M4): every event becomes one delivery per recipient, rendered from plain-text
  templates in Spanish or English and sent through a primary or a secondary e-mail provider with
  timeouts, circuit breakers, failover and backoff. Deliveries end `SENT`, `DEAD` or `CANCELLED`
  across crashes, restarts and several instances; ADMINs list them and retry dead ones; links
  carry their token only in the URL fragment; old events and credentials are purged daily.
- `./mvnw verify` runs formatting, version, architecture, unit and integration checks against
  real PostgreSQL 17 and Redis 8, fails below 80 % line coverage of the domain and application
  layers, and fails if a workspace endpoint is missing from the isolation suite or does not
  declare its minimum role.

Operability and the release come next (M5): verified telemetry, a single-command run, a published
image and the usage guide.

## Run locally

Prerequisites: JDK 21 (Temurin), Docker Desktop (or Docker Engine with Compose) and Git.

1. Start PostgreSQL, Redis and the two simulated e-mail providers:

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

No e-mail leaves your machine: the simulated providers keep every message in their journals,
`http://localhost:8091/__admin/requests` (provider A) and `http://localhost:8092/__admin/requests`
(provider B), where the verification, reset and invitation links can be copied. Links point to
`http://localhost:3001` by default; the `WARDEN_LINK_*` variables of
[`.env.example`](.env.example) change them.

Ephemeral keys do not survive a restart: tokens issued before it become invalid. To keep them,
and to change ports or passwords, see [`.env.example`](.env.example).

The `prod-like` profile behaves like production: the API is served over HTTPS only, on port
`WARDEN_SERVER_PORT` (8443 by default), every response carries `Strict-Transport-Security`, and
the keys and the TLS keystore are mandatory. Startup fails, naming the variable, when one is
missing (REQ-SEC-019, REQ-INST-002). The TLS and key sections of [`.env.example`](.env.example)
explain how to create them.

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
| POST | `/api/v1/workspaces` | Authenticated, verified e-mail |
| GET | `/api/v1/workspaces` | Authenticated |
| GET | `/api/v1/workspaces/{workspaceId}` | VIEWER |
| DELETE | `/api/v1/workspaces/{workspaceId}` | OWNER |
| POST | `/api/v1/workspaces/{workspaceId}/leave` | VIEWER |
| GET | `/api/v1/workspaces/{workspaceId}/members` | VIEWER |
| PUT | `/api/v1/workspaces/{workspaceId}/members/{userId}/role` | ADMIN (OWNER to assign OWNER or change an OWNER) |
| DELETE | `/api/v1/workspaces/{workspaceId}/members/{userId}` | ADMIN (OWNER to remove an OWNER) |
| POST | `/api/v1/workspaces/{workspaceId}/invitations` | ADMIN |
| GET | `/api/v1/workspaces/{workspaceId}/invitations` | ADMIN |
| DELETE | `/api/v1/workspaces/{workspaceId}/invitations/{invitationId}` | ADMIN |
| POST | `/api/v1/invitations/accept` | Authenticated, verified invited e-mail |
| GET | `/api/v1/workspaces/{workspaceId}/notes` | VIEWER |
| POST | `/api/v1/workspaces/{workspaceId}/notes` | MEMBER |
| GET | `/api/v1/workspaces/{workspaceId}/notes/{noteId}` | VIEWER |
| PUT | `/api/v1/workspaces/{workspaceId}/notes/{noteId}` | MEMBER |
| DELETE | `/api/v1/workspaces/{workspaceId}/notes/{noteId}` | MEMBER |
| GET | `/api/v1/workspaces/{workspaceId}/notifications/deliveries` | ADMIN |
| POST | `/api/v1/workspaces/{workspaceId}/notifications/deliveries/{deliveryId}/retry` | ADMIN |
| GET | `/api/v1/me/notification-preferences` | Authenticated |
| PUT | `/api/v1/me/notification-preferences` | Authenticated |

Roles are ordered VIEWER < MEMBER < ADMIN < OWNER; a role grants everything the lower ones do.
For non-members, workspace routes answer 404 exactly as for a workspace that does not exist.
Login, refresh, password recovery, verification resend and invitations are rate limited: past
the limit they answer 429 with `Retry-After`.
Errors follow RFC 9457; the types are listed in [problem-types.md](docs/api/problem-types.md).

## Documentation

- [Software Requirements Specification](docs/requirements/SRS-Warden.md)
- [Software Design Description](docs/design/SDD-Warden.md)
- [Architecture Decision Records](docs/adr/README.md)
- [Threat model](docs/security/threat-model.md)
- [Changelog](CHANGELOG.md)

## License

[Apache License 2.0](LICENSE)