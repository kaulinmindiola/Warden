# Simulated e-mail providers

Two WireMock instances stand in for the e-mail providers A and B (SRS: release 1.0 uses simulated
providers; SDD V09). The same mappings serve the tests and the local Compose environment.

Contract: `POST /v1/messages` with `{"idempotencyKey", "to", "subject", "text"}`.

| Recipient address | Answer | Classification |
|-------------------|--------|----------------|
| `…@invalid.test` | 422 `{"code": "invalid_recipient"}` | `PERMANENT_REJECTION` |
| `…@unavailable.test` | 503 | `TRANSIENT_FAILURE` |
| `…@slow.test` | 202 after 6 s | `TRANSIENT_FAILURE` (5 s request timeout) |
| any other | 202 | `ACCEPTED` |

Requests are kept in WireMock's journal: `GET http://localhost:8091/__admin/requests` (A) and
`:8092` (B) show what was sent, which in the local environment includes the links of the
notifications.