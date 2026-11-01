# ADR-018: Observability stack and trace correlation without a trace backend

- Status: accepted
- Date: 2026-11-01
- Deciders: Kaulin Mindiola
- Related: REQ-OBS-001 to REQ-OBS-008, REQ-PERF-004, REQ-AVAIL-001, CON-007, CON-009; SDD V19, V21; ADR-008, ADR-009

## Context and Problem Statement

Operators need to see how Warden behaves: logins and refreshes, authorization denials, the
notification pipeline (the system-scope dead-letter queue is only observable through telemetry,
DEC-004), and the path of a request from the HTTP call to the e-mails it causes. The reference
environment must stay small (CON-009) and use open-source components only (CON-007), and
telemetry has laxer access controls than the database, so it must not carry secrets or personal
data (REQ-OBS-003, REQ-OBS-005). Which metrics, logging, tracing and alerting components does
release 1.0 use, and how is a delivery correlated with the request that caused it?

## Decision Drivers

- Every signal named by REQ-OBS-001 to 008, with no extra container that adds no requirement.
- No secret, e-mail address or high-cardinality identifier in any signal, enforced by tests.
- Correlation of asynchronous work with its originating request, with no trace backend.
- Components that Spring Boot 4.1 configures natively, so the cost is configuration, not code.

## Considered Options

1. Micrometer with a Prometheus registry and Grafana; Spring Boot structured JSON logs; Micrometer
   Tracing with the OpenTelemetry bridge, no exporter, and the originating trace id persisted with
   events and deliveries; Prometheus alert rules without Alertmanager.
2. Option 1 plus a trace backend (Tempo or Jaeger) receiving spans over OTLP.
3. A full OpenTelemetry pipeline: OTLP for metrics, logs and traces through an OpenTelemetry
   Collector into Prometheus, Loki and Tempo.
4. Logs only, with metrics derived from log lines.

## Decision Outcome

Chosen option: **1**.

- **Metrics.** Micrometer with the Prometheus registry, on the management port (8081,
  `/actuator/prometheus`), which is never published. The application meters are those of SDD V19.
  Tags take fixed values only; the HTTP metrics carry the route template, never the raw path.
  Gauges that count rows (deliveries by status and scope, pending outbox events) show the count of
  the last refresh, every 10 s, so a scrape never queries the database. The outbox lag is a
  histogram with a bucket at the 5 s target of REQ-PERF-004.
- **Logs.** Spring Boot structured logging in the `logstash` JSON format, one object per line, to
  standard output. The MDC adds `traceId` and `spanId` (Micrometer Tracing), `userId` and
  `workspaceId` (a filter in the security chain) and `originTraceId` (the outbox dispatcher and the
  delivery worker). A `StructuredLoggingJsonMembersCustomizer` masks refresh and single-use tokens,
  JWTs and e-mail addresses in every text value of a line, the stack trace included. Request
  details are never logged (`spring.mvc.log-request-details=false`).
- **Traces.** Micrometer Tracing with the OpenTelemetry bridge creates and propagates the trace
  id; no span is exported. The id of the request that stores an event is kept in
  `outbox_event.trace_id` and copied to `notification_delivery.origin_trace_id`; the workers write
  it as `originTraceId`. Searching the logs for one trace id finds the request, the publication of
  its event and every delivery attempt (`docs/operations/log-correlation.md`). An OTLP exporter can
  be enabled later by configuration only.
- **Dashboard and alerts.** Prometheus and Grafana run in the reference environment; Grafana is
  provisioned with one dashboard (REQ-OBS-007), and Prometheus loads the alert rules of SDD V19,
  which are observed as firing in its UI. No Alertmanager: there is no on-call in scope.

### Consequences

- Good: two extra containers (Prometheus, Grafana) cover REQ-OBS-001 to 008; tracing costs one
  dependency and no infrastructure.
- Good: redaction is applied once, where every log line is written, so a careless log statement
  cannot leak a token or an address.
- Good: correlation survives restarts and crosses instances, because the originating trace id is
  stored, not held in memory.
- Bad: no span timeline; diagnosing latency inside a request relies on metrics and logs.
- Bad: alerts are not routed anywhere; someone has to look at Prometheus.
- Bad: the masking is pattern-based; a secret of a new shape needs a new pattern. Tests cover the
  shapes Warden issues.
- Neutral: count gauges may be up to 10 s old.

### Confirmation

- `AccessMetricsIT`, `WorkspaceAuthorizationMetricsTest`, `NotificationMetricsIT` and
  `ProviderMetricsTest`: each operation moves its series by the expected amount; with both
  providers down the breakers show open and the system-scope dead-letter queue grows (REQ-OBS-001,
  002).
- `StructuredLogsIT`, `LogContextFilterTest` and `LogRedactorTest`: every line of an authenticated
  workspace request is JSON with `traceId`, `userId` and `workspaceId`; tokens and e-mails are
  masked in the message, the MDC and the stack trace; the trace of an invitation request locates
  the publication and the delivery attempt (REQ-OBS-004, 005, 006).
- `TelemetryScanIT` (FF-09): after a flow that issues every kind of secret, neither the logs nor any
  metric label contains one, nor an e-mail, nor a user, workspace, note, invitation or session id,
  nor a client address (REQ-OBS-003, 005).
- The dashboard (REQ-OBS-007) and the alert rules are demonstrated in the reference environment
  once it runs Warden with Prometheus and Grafana.

## Pros and Cons of the Options

### Prometheus, Grafana, JSON logs and persisted trace ids

- Good: native Spring Boot support; small footprint; meets every requirement.
- Bad: no span view.

### Adding a trace backend

- Good: span timelines across the request and the workers.
- Bad: a third container and storage for a capability no requirement asks for; REQ-OBS-006 is met
  by log correlation.

### Full OpenTelemetry pipeline

- Good: vendor-neutral transport for every signal.
- Bad: a collector, Loki and Tempo exceed the reference environment (CON-009) for no requirement.

### Logs only

- Good: a single signal.
- Bad: gauges such as breaker state and queue sizes cannot be derived reliably from log lines;
  REQ-OBS-002 and the alerts need real metrics.