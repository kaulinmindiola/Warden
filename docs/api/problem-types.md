# Problem types

Every 4xx and 5xx response of the Warden API is an RFC 9457 problem document with
`Content-Type: application/problem+json` (REQ-INT-005, REQ-COMP-001).

## Members

| Member | Always present | Description |
|--------|----------------|-------------|
| `type` | Yes | Stable URI `urn:warden:problem:<slug>`; clients branch on it, not on `title` or `detail` |
| `title` | Yes | Short, fixed summary of the type |
| `status` | Yes | HTTP status code |
| `detail` | Yes | Human-readable explanation; never contains internals or reveals whether an account exists |
| `instance` | Yes | Path of the request that failed |
| `traceId` | Yes | Trace identifier of the request; quote it when reporting an error |
| `errors` | `validation-error` only | List of `{ "field": "...", "reason": "..." }` |

Example:

```json
{
  "type": "urn:warden:problem:validation-error",
  "title": "Validation error",
  "status": 400,
  "detail": "The request contains invalid values.",
  "instance": "/api/v1/workspaces",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [{ "field": "name", "reason": "must not be blank" }]
}
```

## Catalogue

| Slug | Status | Title | Used when |
|------|--------|-------|-----------|
| `validation-error` | 400 | Validation error | Body, parameter or format validation fails (password policy, lengths, locale, page size); missing or mistyped parameters |
| `malformed-request` | 400 | Malformed request | The body cannot be parsed; a refresh token is sent in the `Authorization` header |
| `invalid-one-time-token` | 400 | Invalid one-time token | A verification, reset or invitation token is unknown, used, expired, revoked or replaced (identical body in every case) |
| `preference-not-optional` | 400 | Preference not optional | Attempt to disable a security or transactional notification |
| `unauthenticated` | 401 | Unauthenticated | Missing, malformed, expired, forged or revoked access token |
| `invalid-credentials` | 401 | Invalid credentials | Unknown e-mail or wrong password (identical body) |
| `invalid-refresh-token` | 401 | Invalid refresh token | Refresh token unknown, expired, reused or of a revoked session (identical body) |
| `insufficient-role` | 403 | Insufficient role | Member below the endpoint's minimum role, or an ADMIN acting on an OWNER |
| `email-not-verified` | 403 | E-mail not verified | Creating a workspace or accepting an invitation without a verified e-mail |
| `invitation-email-mismatch` | 403 | Invitation e-mail mismatch | Verified e-mail differs from the invited one (the token is not consumed) |
| `not-found` | 404 | Not found | Unknown resource or path, workspace of which the caller is not a member, deleted workspace, another user's session |
| `method-not-allowed` | 405 | Method not allowed | HTTP method not supported by the resource; the `Allow` header lists the supported ones |
| `not-acceptable` | 406 | Not acceptable | The `Accept` header asks for an unsupported media type |
| `already-member` | 409 | Already a member | Inviting or accepting for an existing member |
| `email-already-verified` | 409 | E-mail already verified | Resending verification for a verified e-mail |
| `last-owner` | 409 | Last owner | The operation would leave the workspace without an OWNER |
| `version-conflict` | 409 | Version conflict | Stale note version |
| `invitation-not-pending` | 409 | Invitation not pending | Revoking an accepted invitation; reprocessing the delivery of a non-pending invitation |
| `delivery-not-retryable` | 409 | Delivery not retryable | Reprocessing a delivery that is not `DEAD` |
| `payload-too-large` | 413 | Payload too large | Request body larger than 64 KiB |
| `unsupported-media-type` | 415 | Unsupported media type | The request `Content-Type` is not supported |
| `rate-limited` | 429 | Too many requests | Any rate limit; includes `Retry-After` |
| `internal-error` | 500 | Internal error | Unexpected error; generic detail, logged with the `traceId` |
| `service-unavailable` | 503 | Service unavailable | PostgreSQL unavailable, or Redis and PostgreSQL unavailable; includes `Retry-After` |

`method-not-allowed`, `not-acceptable` and `unsupported-media-type` are protocol-level types
produced by the web framework; they extend the catalogue of SDD V08 so that every 4xx response
follows this format.