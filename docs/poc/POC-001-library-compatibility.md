# POC-001 — Library compatibility with Spring Boot 4.1

| Field | Value |
|-------|-------|
| Status | Completed — all libraries compatible, no fallback needed |
| Date | 2026-10-23 |
| Requirement | REQ-POC-001 |
| Risk addressed | RISK-005 (planned libraries incompatible with Spring Boot 4.1) |
| Platform | Java 21 (Temurin 21.0.12), Spring Boot 4.1.2, Maven Wrapper |
| Evidence | `./mvnw verify`: 11 unit tests and 10 integration tests passing |

## Goal

Confirm, before any feature work, that every library the design depends on works on
Spring Boot 4.1, and pin every version so the build is reproducible (REQ-BUILD-003).
Each library has one smoke test that exercises the capability the design needs, not
only that the dependency resolves.

## Results

| Library | Version | Smoke test | Capability proven | Result |
|---------|---------|------------|-------------------|--------|
| Spring Security OAuth2 Resource Server (Nimbus) | Managed by Spring Boot 4.1.2 | `JwtEs256RoundTripTest` | ES256 JWT signed with `NimbusJwtEncoder` and verified with `NimbusJwtDecoder`; a token signed with another key is rejected | Pass |
| Bouncy Castle | 1.86 | `Argon2PasswordHashingTest` | Argon2id hash and verification with m=19 MiB, t=2, p=1 | Pass |
| Hibernate ORM / Spring Data JPA | 7.4.13.Final (managed) | `InfrastructureSmokeIT` | Entity persisted in PostgreSQL 17 and read back with `JdbcClient`; `UuidGenerator.Style.VERSION_7` available | Pass |
| Flyway (+ PostgreSQL module) | Managed by Spring Boot 4.1.2 | `InfrastructureSmokeIT` | Migrations run as the schema owner while the application connects as a separate role | Pass |
| PostgreSQL JDBC driver | Managed by Spring Boot 4.1.2 | `InfrastructureSmokeIT` | Connection to `postgres:17` | Pass |
| Spring Data Redis (Lettuce) | Managed by Spring Boot 4.1.2 | `InfrastructureSmokeIT` | SET/GET on `redis:8` | Pass |
| Bucket4j (core, Lettuce) | 8.21.0 | `LocalBucketControllableClockTest`, `InfrastructureSmokeIT` | Local and Redis-backed buckets refill according to a test-controlled clock | Pass |
| Resilience4j (circuit breaker, time limiter) | 2.4.0 | `ResilienceCircuitBreakerTest` | Breaker opens after the failure threshold and rejects calls; the open-state clock is injectable | Pass |
| Testcontainers | Managed by Spring Boot 4.1.2 (2.x line) | `InfrastructureSmokeIT` | PostgreSQL 17 and Redis 8 containers started and wired into the context | Pass |
| WireMock (standalone, in-JVM) | 3.13.2 | `WireMockDelayedStubTest` | Fixed-delay stub drives JDK `HttpClient` timeouts in both directions | Pass |
| Spring Modulith | 2.1.2 | `ArchitectureTest` | Detects the five modules (`iam`, `notes`, `notification`, `shared`, `workspace`) and `verify()` passes | Pass |
| ArchUnit | 1.5.1 | `ArchitectureTest` | Rule "`shared` depends on no domain module" (SDD V04) is evaluated | Pass |
| Micrometer Prometheus | Managed by Spring Boot 4.1.2 | `WebSmokeIT` | `/actuator/prometheus` served on the management port and not on the server port | Pass |
| Micrometer Tracing (OpenTelemetry bridge) | Managed by Spring Boot 4.1.2 | `WebSmokeIT` | The current span's trace id appears in the log line correlation field | Pass |
| springdoc-openapi (WebMvc API) | 3.1.1 | `WebSmokeIT` | `/v3/api-docs` returns an OpenAPI document | Pass |
| Embedded Tomcat | 11.0.27 (managed) | `WebSmokeIT` | Server and management server start on random ports | Pass |

## Findings that affect the implementation

1. **Tracing on Spring Boot 4 needs two artifacts.** The auto-configuration lives in
   `spring-boot-micrometer-tracing-opentelemetry`, but `OpenTelemetryTracingAutoConfiguration`
   is conditional on `io.micrometer.tracing.otel.bridge.OtelTracer`, which comes from
   `micrometer-tracing-bridge-otel`. With only one of them, Boot falls back to a no-op tracer
   and trace ids are empty.
2. **Observability in tests is opt-in.** `@SpringBootTest` does not configure exporting meter
   registries or reporting tracing components. Tests that need them use
   `@AutoConfigureMetrics` (`spring-boot-micrometer-metrics-test`) and `@AutoConfigureTracing`
   (`spring-boot-micrometer-tracing-test`).
3. **Flyway needs its starter.** On Spring Boot 4, `spring-boot-starter-flyway` is required for
   the auto-configuration; `flyway-core` alone is not enough.
4. **Bucket4j client clock.** In 8.21 the clock is set on the proxy-manager builder with
   `clientClock(TimeMeter)`; there is no `clientSideConfig(...)` builder method.
5. **JWT issuer is not a URL.** `Jwt#getIssuer()` converts the claim to `URL` and fails for an
   issuer such as `warden`. Production code reads it with `getClaimAsString(JwtClaimNames.ISS)`.
6. **UUIDv7 is native.** Hibernate 7.4 provides `UuidGenerator.Style.VERSION_7`, so no custom
   UUIDv7 utility is needed.
7. **Resilience4j time is injectable.** `CircuitBreakerConfig.Builder` exposes `clock` and
   `currentTimestampFunction`; tests can control the open-state wait without short real timeouts.
8. **Testcontainers 2 package names.** `PostgreSQLContainer` is
   `org.testcontainers.postgresql.PostgreSQLContainer` and is no longer generic.
9. **Implicit annotation processing is disabled.** A dependency ships an annotation processor;
   the build passes `-proc:none` because the project uses none. A future processor must be
   declared explicitly.
10. **Mockito runs as a static agent** (`-javaagent` in Surefire and Failsafe, after
    `@{argLine}` to keep JaCoCo), which removes the JDK 21 dynamic agent loading warning.
11. **Open Session in View is enabled by default** and will be disabled
    (`spring.jpa.open-in-view=false`) when the application configuration is created.

## Fallbacks

No fallback was applied. The prepared alternatives remain documented in case a future
upgrade breaks compatibility:

| Library | Fallback |
|---------|----------|
| Spring Modulith | Equivalent rules in ArchUnit |
| springdoc-openapi | Hand-written `docs/api/openapi.yaml` |
| Bucket4j | `INCR`/`EXPIRE` counters behind the same port (ADR-014) |
| Resilience4j clock | Short timeouts in the `test` profile |

## Scope of the smoke tests

The smoke tests in `src/test/java/.../poc/` stay in the repository as compatibility
regression tests. `InfrastructureSmokeIT` and `WebSmokeIT` start the context without the
shared test infrastructure; they will be migrated to it once that infrastructure exists.

## How to reproduce

```bash
./mvnw verify
```

Docker must be running for the integration tests.