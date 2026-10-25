# ADR-011: Protection of link tokens inside events and deliveries

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: REQ-SEC-004, REQ-SEC-005, REQ-INST-002, DEC-044, RISK-011; SDD V06, V10, V13, V18; ADR-008, ADR-017

## Context and Problem Statement

E-mail verification, password reset and invitations send a link that carries a one-time
token. The module that creates the token (`iam` or `workspace`) is not the module that sends
the e-mail (`notification`): the token travels through the transactional outbox (ADR-008) and
then waits in a delivery row until a provider accepts the message, possibly after retries.

Warden stores only the SHA-256 hash of every token it validates (REQ-SEC-004), so the
plaintext cannot be recovered later. REQ-SEC-005 requires that the token never rests in
plaintext in events or deliveries. How does the token reach the e-mail without existing in
plaintext at rest?

## Decision Drivers

- No plaintext token in `outbox_event`, `notification_delivery`, logs or backups.
- `notification` must not call back into `iam` or `workspace` (REQ-MAINT-002, ADR-017).
- Deliveries must survive restarts and retries (REQ-REL-001 to REQ-REL-006).
- Key rotation without losing tokens that are already in flight.
- Keys come only from the environment (REQ-INST-002).

## Considered Options

1. Plaintext token in the event payload and the delivery row.
2. `notification` asks the producing module for a fresh token at send time.
3. Send the e-mail synchronously inside the request, without storing the token.
4. Token sealed with AES-256-GCM in the event and the delivery, removed as soon as it is no
   longer needed.

## Decision Outcome

Chosen option: **4, AES-256-GCM sealed secret with a limited lifecycle**.

**Format** (`shared.crypto.SecretCipher`, `SealedSecret`):

- The payload field is `Secret = {ciphertext, keyId, expiresAt}` (SDD V10).
- `ciphertext` is Base64 of a random 96-bit nonce followed by the AES-GCM ciphertext and its
  128-bit tag. Every seal uses a fresh nonce.
- `keyId` and `expiresAt` are authenticated as associated data
  (`warden-secret-v1|<keyId>|<expiresAt epoch millis>`). Changing either one, or any byte of
  the ciphertext, makes opening fail.
- `expiresAt` is truncated to milliseconds so it survives a round trip through PostgreSQL.
- Opening fails with an explicit reason: `UNKNOWN_KEY`, `EXPIRED` or `TAMPERED`. No message
  contains the secret or the ciphertext.

**Keys.** `WARDEN_SECRET_KEYS=id:base64key[,id:base64key]`, with 32-byte keys. The first key
seals; every listed key opens. To rotate keys, put the new key first and keep the old one until
the longest lifetime of a sealed secret has passed, then remove it. Outside the local profile the
application refuses to start without keys.

**Lifecycle:**

1. The producing service generates the token, stores only its hash and seals the plaintext into
   the event payload, in the same transaction (steps 1.11, 2.x).
2. The dispatcher copies the sealed secret into `notification_delivery.secret_ciphertext` and
   `secret_expires_at`, and strips `payload.secret` from `outbox_event` in the same transaction
   that creates the deliveries (SDD V10, phase 4).
3. The delivery worker opens the secret only to render the message, in memory.
4. The secret is set to null when the delivery reaches `SENT` or `CANCELLED`, or when it expires
   (phase 4, step 4.11).

### Consequences

- Good: a database dump, a backup or a log line never exposes a usable token without the key.
- Good: tampering and replays with an extended expiry are detected, not silently accepted.
- Good: `notification` stays independent; it needs only the shared key ring.
- Bad: losing every key in `WARDEN_SECRET_KEYS` makes in-flight links unusable. Users must
  request a new link; no other data is lost.
- Bad: the plaintext exists in process memory while a message is rendered. This is unavoidable
  for sending the link at all.
- Neutral: ephemeral keys in the local profile mean that sealed secrets do not survive a restart
  there.

### Confirmation

- `SecretCipherTest`: round trip, a fresh nonce for every seal, rejection of an altered
  ciphertext, an extended expiry, a relabelled key id, malformed input, an unknown key id and an
  expired secret, and opening across a key rotation.
- `KeyMaterialLoaderTest`, `KeyConfigurationTest`: key ring parsing and startup failure without
  keys.
- Planned: step 1.11 (the token sealed in `iam.EmailVerificationRequested`, and only its hash in
  `one_time_token`); step 4.11 (the secret stripped at dispatch and nulled on
  SENT/CANCELLED/expiry).

## Pros and Cons of the Options

### Plaintext token

- Good: simplest.
- Bad: violates REQ-SEC-005; any database read access yields working links.

### Callback to the producing module

- Good: nothing secret is stored.
- Bad: impossible without storing the plaintext, because only the hash is kept. A new token
  per attempt would invalidate the one already e-mailed. It also couples `notification` to
  `iam` and `workspace`.

### Synchronous send

- Good: the token never leaves the request.
- Bad: loses the outbox guarantees, retries and failover (REQ-REL-001 to REQ-REL-006), and
  provider latency is added to the request.

### Sealed secret with a lifecycle

- Good: meets REQ-SEC-005 and keeps reliability and module independence.
- Bad: requires key management and rotation discipline.