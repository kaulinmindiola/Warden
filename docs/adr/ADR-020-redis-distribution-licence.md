# ADR-020: Redis distribution and licence

- Status: accepted
- Date: 2026-10-25
- Deciders: Kaulin Mindiola
- Related: CON-004, CON-007, DEP-002, REQ-COST-001; SDD V06, V21

## Context and Problem Statement

Warden uses Redis for the session revocation denylist and for rate-limit buckets (CON-004).
The project may only depend on open-source software (CON-007), and the release must include a
licence report (REQ-COST-001). Redis 7.4 is distributed under source-available licences that
are not OSI-approved. Redis 8 adds the OSI-approved AGPLv3 as an option, and Valkey is a
BSD-licensed fork. Which Redis-compatible server satisfies both constraints?

## Decision Drivers

- OSI-approved licence for every runtime component.
- Compatibility with the clients and libraries validated in POC-001 (Lettuce, Bucket4j).
- No change to Warden's own licence (Apache-2.0).
- An easy exit if the licence situation changes.

## Considered Options

1. Redis 7.4.
2. Redis 8, used under the AGPLv3 option.
3. Valkey.

## Decision Outcome

Chosen option: **2, Redis 8 under AGPLv3**, run unmodified as a separate, self-hosted process
from the official `redis:8` image. Valkey is the documented drop-in fallback.

Warden does not link, embed or modify Redis; it talks to it over the network protocol.
Warden's own code therefore stays under Apache-2.0. Running an unmodified AGPLv3 server does
not require publishing Warden's source; the AGPLv3 obligations concern modified versions of
Redis offered over a network. This is an engineering assessment, not legal advice.

### Consequences

- Good: an OSI-approved licence with the reference implementation that CON-004 names.
- Good: the licence report lists Redis with its licence explicitly.
- Bad: AGPLv3 would impose obligations if Redis itself were ever modified; Warden must keep it
  unmodified.
- Neutral: Warden uses no Redis 8-specific module or data type, so moving to Valkey means
  changing one image reference in `compose.yaml` and in the test containers, then running the
  suite.

### Confirmation

- POC-001 (`InfrastructureSmokeIT`): Spring Data Redis with Lettuce and Bucket4j on Lettuce
  work against `redis:8`.
- `IntegrationTestSupportIT#redisIsReachable` runs on every build against `redis:8`.

## Pros and Cons of the Options

### Redis 7.4

- Good: widely deployed.
- Bad: not OSI-approved; violates CON-007.

### Redis 8 (AGPLv3)

- Good: OSI-approved option, reference implementation, current releases.
- Bad: copyleft obligations if modified.

### Valkey

- Good: permissive BSD licence; protocol-compatible.
- Bad: not literally "Redis" as CON-004 states; kept as the fallback rather than the default.