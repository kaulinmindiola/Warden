# ADR-002: Self-issued tokens instead of an external identity provider

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: REQ-INT-003, REQ-INT-004, REQ-SEC-001, REQ-SEC-002, REQ-FUNC-008 to REQ-FUNC-021, CON-006, DEC-051; SDD V01, V08, V11, V18; ADR-006, ADR-007, ADR-012

## Context and Problem Statement

Warden authenticates users, keeps their sessions and must revoke them immediately (G-02). The
portfolio's core competence is precisely this identity and session management (CON-006). Should
Warden issue and validate its own tokens, or delegate authentication to an identity provider?

## Decision Drivers

- Full control over session semantics: per-login sessions, refresh rotation with reuse
  detection, immediate revocation, enumeration resistance (REQ-FUNC-008 to REQ-FUNC-021).
- A reference environment that starts with one command and few containers (REQ-INST-001).
- Standard formats that any client library understands (REQ-COMP-002).
- No external federation requirement: MFA, OAuth2/OIDC and SSO are out of scope (SRS §1.2).

## Considered Options

1. Warden issues and validates its own tokens.
2. An external identity provider (for example Keycloak) issues tokens; Warden only validates
   them.
3. An embedded authorization server (Spring Authorization Server) inside Warden.

## Decision Outcome

Chosen option: **1, self-issued tokens**.

- Access tokens are short-lived ES256 JWTs (10 minutes) that carry only `iss`, `sub`, `sid`,
  `jti`, `iat` and `exp` (ADR-012).
- Refresh tokens are opaque 256-bit values with the `wrt_` prefix; only their SHA-256 hash is
  stored, bound to a session row (ADR-006).
- Warden is the only issuer and the only validator. No JWKS endpoint is published in 1.0,
  because no third party verifies Warden's tokens.
- Revocation relies on the `sid` claim: a revoked session is rejected on the next request
  (ADR-007).

### Consequences

- Good: every session rule of the SRS is implemented and tested in Warden's own code.
- Good: no additional container or protocol to operate; the API stays a simple JSON contract.
- Bad: Warden owns security-sensitive code (password hashing, token signing, rotation). This is
  mitigated by the strict validation tests and the threat model.
- Bad: federation (SSO, social login) would need a new decision; it is out of scope for 1.0.

### Confirmation

- `LoginIT`: login issues exactly the specified tokens and session.
- `JwtIssuerTest`, `AccessTokenAuthenticationIT`: the token contents and strict validation.
- `AuthenticationByDefaultIT`: every non-public endpoint requires the token.

## Pros and Cons of the Options

### Self-issued tokens

- Good: complete control and testability of session behaviour; minimal infrastructure.
- Bad: security-critical code to own.

### External identity provider

- Good: mature, feature-rich, federation-ready.
- Bad: refresh rotation, reuse detection and immediate revocation become provider
  configuration rather than Warden behaviour; adds a heavy container; moves the core competence
  out of the project.

### Embedded authorization server

- Good: standards-complete OAuth2 flows.
- Bad: brings OAuth2 grant machinery that the SRS does not require; more surface than the
  password-login use case needs.