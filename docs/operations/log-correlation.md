# Following a request through the logs

Warden writes one JSON object per log line to standard output (REQ-OBS-004). This page shows how
to follow one request, from the HTTP call to the e-mails it caused (REQ-OBS-006).

## Fields

| Field | Present on | Meaning |
|-------|------------|---------|
| `@timestamp`, `level`, `logger_name`, `thread_name`, `message` | every line | Standard fields of the `logstash` format |
| `traceId`, `spanId` | lines written while a request is handled | The trace of that request |
| `userId` | authenticated requests | The caller |
| `workspaceId` | requests to `/api/v1/workspaces/{id}/…` | The workspace named in the path |
| `originTraceId` | lines of the outbox dispatcher and the delivery worker | The `traceId` of the request that stored the event or produced the delivery |

The workers run outside any request, so their own `traceId` differs from the request's. The
originating trace is stored with each event (`outbox_event.trace_id`) and each delivery
(`notification_delivery.origin_trace_id`) and written as `originTraceId`.

## Finding a request

1. Take the `traceId`: from the problem details of an error response, or from any line of the request.
2. Search the logs for it. Lines of the request match on `traceId`; lines of the workers match on `originTraceId`:

   ```powershell
   docker compose logs warden | Select-String '<traceId>'
   ```

   ```bash
   docker compose logs warden | grep '<traceId>'
   ```

For an invitation, the search returns:

- the lines of the `POST /api/v1/workspaces/{id}/invitations` request, if any were written;
- `Outbox event <id> of type <type> published`, when the event reached the notification module;
- `Delivery <id> is <status> after <n> attempts (provider <name>)`, once per recorded attempt.

## What the logs never contain

Passwords, tokens, the `Authorization` header and request or response bodies are never logged.
As a safety net, every text value of a line is redacted before it is written: refresh and
single-use tokens keep only their prefix (`wrt_[REDACTED]`, `wot_[REDACTED]`), and JSON Web Tokens
and e-mail addresses are replaced with `[REDACTED-JWT]` and `[REDACTED-EMAIL]` (REQ-OBS-005).