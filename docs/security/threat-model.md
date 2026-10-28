# Threat model

- Version: 1 (milestone M2, `v0.3.0`)
- Date: 2026-10-28
- Scope: authentication and sessions, the `iam` module and the `shared` security kernel (v0); workspaces, roles, invitations, notes and tenant isolation (v1)
- Method: STRIDE per element; each threat is traced to its misuse case (MUC), requirement and test
- Next revision: milestone M3 (abuse protection and degraded modes)

## System under analysis

```mermaid
flowchart LR
    C["API client"] -->|HTTPS, Bearer token| A["Warden API /api/v1"]
    A -->|SQL as warden_app| P[("PostgreSQL 17")]
    A -->|revoked sessions| R[("Redis 8")]
    A -->|sealed link tokens| O[("outbox_event")]
    O -.->|phase 4| N["notification module, then e-mail providers"]
```

| Element | Holds | Trust boundary |
|---------|-------|----------------|
| API client | Password, access token (10 min), refresh token (≤ 7 days, session ≤ 30 days) | Untrusted; every request crosses the boundary |
| Public endpoints | `register`, `email/verify`, `login`, `refresh`, `password/forgot`, `password/reset` | Reachable without a token (REQ-SEC-001) |
| `user_account`, `one_time_token`, `auth_session`, `refresh_token` | Argon2id hashes, SHA-256 token hashes, session state | Database; the runtime role owns no table |
| Workspace authorization interceptor | The caller's membership and role, resolved on every request and never cached | Every `/workspaces/{workspaceId}/**` handler; fails closed (404, 403, 503) |
| `workspace`, `workspace_member`, `note`, `invitation` | Tenant data, roles, invitation token hashes | Database; all but `workspace` under forced Row Level Security keyed by transaction-local settings (`app.workspace_id`, `app.user_id`, `app.invitation_token_hash`) |
| `outbox_event` | Event payloads with recipient data and AES-256-GCM sealed link tokens | Database; read by the notification module in phase 4 |
| `audit_log` | Security-relevant actions with `traceId` | Database; the runtime role cannot update, delete or truncate |
| Redis | Revoked session ids with a 630 s TTL | Ephemeral; PostgreSQL stays authoritative |
| Key material | ES256 signing key, AES-256 key ring, HMAC key | Environment variables; never in the repository |

## Threats

Status: **Mitigated** (control implemented and tested), **Partial** (part of the control is
implemented; the remainder is named), **Open** (planned in a later phase), **Accepted** (residual
risk recorded).

### Spoofing

| ID | Threat | Control | MUC | Requirement | Test | Status |
|----|--------|---------|-----|-------------|------|--------|
| S-01 | Forged or altered access token: `alg: none`, another algorithm, bad signature, other issuer, missing `sid` or `jti`, expired | Nimbus decoder pinned to ES256 with explicit validators and 30 s skew (ADR-012) | — | REQ-SEC-002, REQ-INT-004 | `AccessTokenAuthenticationIT.forgedOrIncompleteTokensAreRejected` | Mitigated |
| S-02 | Use of a stolen refresh token | Rotation on every use; presenting a used token revokes the whole session (ADR-006) | MUC-02 | REQ-FUNC-011, 012, BR-005 | `RefreshIT`, `RefreshReuseAndExpiryIT` | Mitigated |
| S-03 | Racing a stolen refresh token against its owner to obtain two token families | Conditional `UPDATE … RETURNING`: exactly one winner, the session ends revoked | MUC-02 | REQ-FUNC-013 | `ConcurrentRefreshIT` (50 × 4 concurrent requests) | Mitigated |
| S-04 | Replay of a verification or password reset link | Single use, conditional consumption, expiry (24 h / 30 min), a new link invalidates the previous one | MUC-06 | REQ-FUNC-006, 007, 019, 021, BR-006 | `EmailVerificationIT`, `ResendVerificationIT`, `PasswordResetIT` | Mitigated |
| S-05 | Guessing a link or refresh token | 256-bit random tokens from `SecureRandom`; only their hashes are stored | MUC-02, MUC-06 | REQ-SEC-004 | `SecureTokenGeneratorTest` | Mitigated |
| S-06 | A token from one purpose used for another (verification token on reset) | Consumption filters by purpose; refresh and link tokens have distinct prefixes | MUC-06 | REQ-FUNC-021 | `PasswordResetIT.unknownUsedWrongPurposeAndMalformedTokensGetTheSameResponseAndChangeNothing` | Mitigated |
| S-07 | Brute force and credential stuffing on login | Argon2id slows each guess; per-account and per-IP limits | MUC-03 | REQ-SEC-006, 007 | — | Open (rate limiting, phase 3) |
| S-08 | Accepting an invitation sent to someone else (forwarded or intercepted link) | The caller's e-mail must be verified and equal to the invited one; otherwise 403 without consuming the token, audited as a failure | MUC-06 | REQ-FUNC-036, BR-009 | `AcceptInvitationIT.anotherOrAnUnverifiedAccountGets403AndTheTokenStaysUsable` | Mitigated |
| S-09 | Replay of an invitation link: used, revoked, replaced, expired or of a deleted workspace | Conditional update on a pending, unexpired row under the workspace lock; re-inviting replaces, deleting revokes; every case gets the same 400 | MUC-06 | REQ-FUNC-032, 035, 036, BR-006 | `AcceptInvitationIT.unknownUsedExpiredRevokedReplacedAndDeletedWorkspaceTokensGetTheSameResponse` | Mitigated |

### Tampering

| ID | Threat | Control | MUC | Requirement | Test | Status |
|----|--------|---------|-----|-------------|------|--------|
| T-01 | Altering a sealed link token, its key id or its expiry in the outbox | AES-256-GCM with key id and expiry as associated data (ADR-011) | MUC-06 | REQ-SEC-005 | `SecretCipherTest` (altered ciphertext, extended expiry, relabelled key) | Mitigated |
| T-02 | Altering or deleting audit records through the application | `UPDATE`, `DELETE` and `TRUNCATE` revoked from the runtime role | — | REQ-SEC-016 | `AuditLogIT.theApplicationRoleCannotModifyOrDeleteAuditRecords` | Mitigated |
| T-03 | Changing the password of an account without owning its e-mail | Reset requires a single-use token sent to the e-mail; the policy is enforced again | MUC-06 | REQ-FUNC-020, 021 | `PasswordResetIT` | Mitigated |
| T-04 | An invitation holder escalating the invited role, or rewriting another invitation, through SQL | Acceptance runs in a token-holder context (`forInvitationHolder`, a refinement of the `forUser` context of SDD V14): Row Level Security exposes only the row of that token hash and lets it change only to `ACCEPTED`; the runtime role may update only `invitation.status` and cannot delete invitations | MUC-05 | REQ-SEC-012, BR-008 | `InvitationRowLevelSecurityIT`, `TenantTransactionsIT` | Mitigated |
| T-05 | Lost update when two members edit the same note | Optimistic version; a stale edit gets 409 `version-conflict` | — | REQ-FUNC-045 | `ConcurrentNoteEditIT` (50 repetitions) | Mitigated |
| T-06 | Concurrent demotions or removals leaving a workspace without OWNER | Workspace row locked with `SELECT … FOR UPDATE` and owners counted under the lock (SDD V14-A4) | MUC-05 | REQ-FUNC-039, BR-001, BR-011 | `ConcurrentLastOwnerIT` (50 repetitions), `MemberRemovalIT` | Mitigated |

### Repudiation

| ID | Threat | Control | MUC | Requirement | Test | Status |
|----|--------|---------|-----|-------------|------|--------|
| R-01 | A user or attacker denies a login, logout, session termination, refresh reuse, password reset or a workspace action (creation, role change, removal, leave, invitation, acceptance, deletion) | Audit record with actor, action, outcome and `traceId`; failures recorded even when the transaction rolls back; refused invitation acceptances recorded as failures | — | REQ-SEC-015 | `AuditLogIT`, `SessionManagementIT`, `RefreshReuseAndExpiryIT`, `PasswordResetIT`, `InvitationIT`, `AcceptInvitationIT`, `WorkspaceDeletionIT` | Partial (one-record-per-action sweep and retention pending) |

### Information disclosure

| ID | Threat | Control | MUC | Requirement | Test | Status |
|----|--------|---------|-----|-------------|------|--------|
| I-01 | Account enumeration through status codes or bodies of register, login and forgot-password | Identical responses for existing and unknown e-mails | MUC-04 | REQ-FUNC-002, 009, 019, BR-007 | `RegistrationIT`, `LoginIT`, `PasswordResetIT` (`assertIdenticalResponses`) | Mitigated |
| I-02 | Account enumeration through response time | Dummy Argon2 hash on login; 250 ms minimum response time on register and forgot-password (ADR-019) | MUC-04 | REQ-SEC-010 | `ResponseFloorTest`, `ResponseFloorIT` | Partial (statistical analysis and calibration pending) |
| I-03 | A database leak exposes usable tokens or passwords | Argon2id for passwords, SHA-256 for refresh, link and invitation tokens, sealed link tokens in events | MUC-02, MUC-06 | REQ-SEC-003, 004, 005 | `SecretsAtRestIT` (including an invitation flow), `AccountPersistenceIT` | Mitigated for storage; deletion of sealed tokens after delivery in phase 4 |
| I-04 | Tokens leaked through URLs, proxies or logs of query strings | Access token only in the `Authorization` header, refresh and invitation tokens only in the body | — | REQ-INT-002 | `AccessTokenAuthenticationIT.aTokenInTheQueryStringIsIgnored`, `RefreshIT` | Mitigated |
| I-05 | Error responses reveal stack traces, SQL or class names | Fixed problem-type catalogue; unexpected errors render a generic 500 with `traceId` | — | REQ-SEC-017 | `ProblemDetailsContractIT.anUnexpectedExceptionRevealsNoInternals` | Mitigated |
| I-06 | Secrets written to logs or audit records | Request records redact secrets and e-mails in `toString`; audit details reject token-like values and never carry e-mails | — | REQ-SEC-014, REQ-OBS-005 | `AuditEntryTest.rejectsDetailsThatLookLikeTokens`, `SecretCipherTest.neverRevealsTheSecretInTextualForms` | Partial (log scan in phase 5) |
| I-07 | The access token exposes personal or authorization data | Claims limited to `iss`, `sub`, `sid`, `jti`, `iat`, `exp`; roles stay on the server (ADR-003) | — | REQ-INT-004 | `LoginIT.validCredentialsStartASessionAndReturnExactlyTheSpecifiedTokens` | Mitigated |
| I-08 | Reading or modifying another workspace's data through its id, or through a resource id of X under the path of W | Membership resolved on every request; the effective tenant is always the one in the path and every query names it; resources of another workspace answer 404 | MUC-01 | REQ-FUNC-023, 025 | `IsolationSuiteIT`, `NoteCrossWorkspaceIT`, `MemberRoleIT`, `MemberRemovalIT`, `InvitationIT` | Mitigated |
| I-09 | A forgotten application filter or a native query returns rows of another tenant (RISK-009) | Forced Row Level Security on tenant tables keyed by transaction-local settings; with no context no row is visible (ADR-005) | MUC-01 | REQ-SEC-012 | `NoteRowLevelSecurityIT`, `InvitationRowLevelSecurityIT` | Mitigated |
| I-10 | Tenant context leaking to the next transaction on a pooled connection | `set_config(…, true)` scopes settings to the transaction; tenant transactions refuse to run nested (POC-002) | MUC-01 | REQ-SEC-012 | `TenantTransactionsIT`, POC-002 | Mitigated |
| I-11 | Workspace existence revealed to non-members, or a deleted workspace still reachable | Non-member, foreign and deleted workspaces answer exactly like a random id (ADR-004, BR-012) | MUC-01 | REQ-FUNC-023, 026 | `IsolationSuiteIT` (foreign and deleted cases on every endpoint) | Mitigated |
| I-12 | Invitation tokens exposed through the API | Only the SHA-256 is stored; creation and listing responses never contain the token or its hash; it travels only sealed in `workspace.MemberInvited` | MUC-06 | REQ-FUNC-034, REQ-SEC-004, 005 | `InvitationIT` | Mitigated |
| I-13 | A VIEWER reads the e-mail addresses of the other members (RISK-010) | Accepted by design: member listing shows e-mails to every member | — | REQ-FUNC-037 | `WorkspaceMembersIT` | Accepted |

### Denial of service

| ID | Threat | Control | MUC | Requirement | Test | Status |
|----|--------|---------|-----|-------------|------|--------|
| D-01 | Memory exhaustion through many concurrent Argon2 computations | Fair semaphore of 4 permits around hashing (ADR-010) | MUC-03 | REQ-SEC-003 | `BoundedPasswordHasherTest.neverRunsMoreHashesAtOnceThanAllowed` | Mitigated |
| D-02 | Oversized request bodies | 64 KiB limit before deserialization | — | REQ-SEC-018 | `ApiConventionsIT` | Mitigated |
| D-03 | Redis unavailable makes revocation fail open | The validator falls back to PostgreSQL when Redis cannot answer | — | REQ-REL-007 | `RevocationFallbackIT` | Partial (503 when both stores fail and reconciliation, phase 3) |
| D-04 | Using Warden to flood an inbox with verification or reset e-mails | Per-account and per-e-mail limits on resend and forgot-password | MUC-07 | REQ-FUNC-007, REQ-SEC-008 | — | Open (rate limiting, phase 3) |
| D-05 | Using invitations to send spam, including across several workspaces | 50 invitations per workspace and 100 per inviting user per day | MUC-07 | REQ-FUNC-033 | — | Open (rate limiting, phase 3) |

### Elevation of privilege

| ID | Threat | Control | MUC | Requirement | Test | Status |
|----|--------|---------|-----|-------------|------|--------|
| E-01 | Calling a protected endpoint without a token | Deny by default; only six public endpoints, matched exactly | — | REQ-SEC-001 | `AuthenticationByDefaultIT` (sweep of every handler mapping) | Mitigated |
| E-02 | A revoked session keeps working until its access token expires | Revocation marked in Redis before the response; checked on every request | MUC-02 | REQ-FUNC-018 | `SessionRevocationIT`, `RefreshReuseAndExpiryIT`, `PasswordResetIT` | Mitigated |
| E-03 | Terminating or inspecting the session of another user | Ownership check; another user's session answers 404 | — | REQ-FUNC-016, 017 | `SessionManagementIT` | Mitigated |
| E-04 | Stale or forged roles in the token | Roles are never in the token; the current membership is read on every request, so a demotion or removal applies on the next request (ADR-003) | MUC-05 | REQ-FUNC-022, BR-004 | `MemberRoleIT.aDemotedAdminIsRejectedOnTheNextRequestWithTheSameToken`, `WorkspaceMembersIT` | Mitigated |
| E-05 | A role below the minimum calling a privileged workspace endpoint | Every workspace handler declares `@RequiresWorkspaceRole` (FF-05); 403 `insufficient-role` | MUC-05 | REQ-FUNC-024 | `RoleMatrixIT` (14 endpoints × 4 roles), `WorkspaceEndpointRulesIT` | Mitigated |
| E-06 | An ADMIN granting OWNER, changing or removing an OWNER, or inviting with OWNER | Authority rules re-checked under the workspace lock; OWNER is not invitable (database `CHECK` too) | MUC-05 | REQ-FUNC-031, 038, 041, BR-002, BR-008 | `MemberRoleIT`, `MemberRemovalIT`, `InvitationIT` | Mitigated |
| E-07 | A new workspace endpoint shipped without isolation coverage | R7: the build fails when a workspace handler is missing from the isolation registry; registered endpoints inherit the foreign and deleted workspace cases and the role matrix | MUC-01 | REQ-MAINT-003 | `WorkspaceEndpointRulesIT`; failing CI shown on PR #18 | Mitigated |
| E-08 | A failing membership lookup or an unknown stored role grants access | Fail closed: lookup errors give 503, an unknown role means no access (404) and is logged | MUC-01 | REQ-SEC-011 | `WorkspaceAuthorizationInterceptorTest`, `WorkspaceMembershipLookupTest` | Partial (fault injection with PostgreSQL down, phase 3) |

## Residual and accepted risks

| ID | Risk | Decision |
|----|------|----------|
| RISK-008 | A client that retries a refresh with the same token after a timeout loses its session | Accepted and documented in ADR-006: clients must not retry a refresh with the same token |
| RISK-010 | A VIEWER can see the e-mail addresses of the other members | Accepted in the SRS (I-13) |
| RISK-012 | Without Redis, rate limits are per instance | Accepted for release 1.0 (REQ-REL-008); applies from phase 3 |
| — | Ephemeral keys in the local profile invalidate tokens and sealed secrets on restart | Accepted for local development only; the `prod-like` profile refuses them (REQ-INST-002) |
| — | The response floor adds at least 250 ms to registration and password reset requests | Accepted (ADR-019); these endpoints are outside the REQ-PERF-001 latency targets or account for the floor |
| — | The token-holder policy trusts the token hash: someone able to read `invitation.token_hash` could act as holder at SQL level | Accepted: that requires database access, which already exposes all tenant data; the API never returns hashes |
| — | Invitation, revocation and deletion events are recorded but not consumed until phase 4, so their cancellations are applied then | Accepted until M4; until then no notification is sent at all |

## Open items for the next revision

- S-07, D-04 and D-05: rate limiting, including the invitation limits (phase 3).
- I-02: REQ-SEC-010 analysis and calibration of the response floor.
- D-03 and E-08: behaviour with Redis and PostgreSQL unavailable, denylist reconciliation and fault injection for the membership lookup (phase 3).
- R-01: one-record-per-action sweep of REQ-SEC-015 and audit retention (REQ-SEC-016).
- Cancellation of deliveries of revoked or replaced invitations and of deleted workspaces (REQ-FUNC-030, 032, 035, 062): phase 4.