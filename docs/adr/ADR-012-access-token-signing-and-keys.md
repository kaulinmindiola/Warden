# ADR-012: Access-token signing algorithm and key management

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: REQ-INT-004, REQ-SEC-002, REQ-INST-002, REQ-COMP-002, TBD-03; SDD V08, V18, Appendix D; ADR-002

## Context and Problem Statement

REQ-INT-004 requires an asymmetric signature and leaves the algorithm to an ADR (TBD-03). The
signing key must come from the environment, never from the repository, and the application must
refuse to start without it outside the local profile (REQ-INST-002). Which algorithm, key
format, key identification and rotation does Warden use?

## Decision Drivers

- Asymmetric signature, so verification never needs the private key.
- Small tokens and fast signing on every login and refresh.
- A key format that can be passed through an environment variable.
- Strict validation that rules out algorithm confusion (REQ-SEC-002).
- Verifiable by standard tools independent of Warden (REQ-COMP-002).

## Considered Options

1. ES256 (ECDSA on P-256 with SHA-256).
2. RS256 (RSA PKCS#1 v1.5 with SHA-256).
3. EdDSA (Ed25519).

## Decision Outcome

Chosen option: **1, ES256**.

**Keys (step 1.4):**

- `WARDEN_JWT_SIGNING_KEY` holds a PKCS#8 P-256 private key, as PEM, PEM with escaped newlines
  or single-line Base64. `WARDEN_JWT_KEY_ID` sets the `kid` header. The public key is derived
  from the private key.
- The local profile generates an ephemeral key when none is configured; the `prod-like`
  profile, and any start without the local opt-in, fails naming the missing variables.

**Issuing (step 1.14):** header `alg=ES256` and `kid`; claims exactly `iss`, `sub`, `sid`,
`jti`, `iat` and `exp = iat + 600`; `iat` truncated to seconds.

**Validation (step 1.15):** the decoder accepts only ES256 with the configured key, so unsigned
tokens, HS256 tokens keyed with the public key and foreign signatures fail. `exp` and `nbf` are
checked with a 30-second skew against the application clock; `iss` must match exactly; `sub` and
`sid` must be UUIDs; `jti`, `iat` and `exp` must be present.

**Rotation (deviation from SDD V18).** The SDD baseline accepted the current and the previous
public key during verification. Release 1.0 accepts **only the current key**. Rotating the key
invalidates outstanding access tokens, which live at most 10 minutes, but not sessions: clients
get a 401, refresh with their opaque refresh token, which does not depend on the signing key,
and receive a token signed with the new key. The cost of a rotation is at most one extra
refresh per client. The `kid` header keeps multi-key verification possible if a future release
needs zero-refresh rotation.

### Consequences

- Good: 64-byte signatures and small keys; fast signing and verification.
- Good: algorithm confusion is impossible by construction: the verifier knows a single
  algorithm and a single key.
- Good: the key is configuration, never source; ephemeral keys keep local development simple.
- Bad: ECDSA needs a secure random nonce per signature; the JDK provider supplies it.
- Bad: a key rotation forces one refresh per active client (accepted above).

### Confirmation

- `KeyMaterialLoaderTest`, `KeyConfigurationTest`: key formats, P-256 enforcement and startup
  failure without keys.
- `JwtIssuerTest`, `LoginIT`: header and exact claims.
- `AccessTokenAuthenticationIT`: the forgery matrix (unsigned, HS256 with the public key,
  foreign key, wrong issuer, missing `sid` or `jti`, non-UUID `sub`), the 29-second and
  31-second expiry cases, and tokens in the query string ignored.
- `AccessTokenAuthenticationIT#anIndependentVerifierValidatesIssuedTokensWithThePublicKey`: the
  JDK's own `SHA256withECDSAinP1363Format` verifies the signature with the public key. This JDK
  check replaces the separate test-scope JWT library suggested in planning; it is fully
  independent of Nimbus and Spring and adds no dependency.

## Pros and Cons of the Options

### ES256

- Good: compact, fast, widely supported (RFC 7518).
- Bad: needs a good randomness source per signature.

### RS256

- Good: the most widely supported.
- Bad: 256-byte signatures and larger keys; slower signing.

### EdDSA (Ed25519)

- Good: deterministic, fast, compact.
- Bad: less uniform support in JWT libraries and resource-server tooling at the time of the
  decision.