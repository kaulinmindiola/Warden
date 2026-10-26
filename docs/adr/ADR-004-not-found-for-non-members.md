# ADR-004: 404 for non-members, 403 for an insufficient role

- Status: accepted
- Date: 2026-10-26
- Deciders: Kaulin Mindiola
- Related: REQ-FUNC-022, REQ-FUNC-023, REQ-FUNC-024, REQ-SEC-011, REQ-MAINT-003, BR-004, BR-012, DEC-015, MUC-01; SDD V07, V08, V12, R7, FF-04, FF-05; ADR-003, ADR-005

## Context and Problem Statement

Every route under `/api/v1/workspaces/{workspaceId}/**` must refuse callers who are not members
and members whose role is too low. The status codes are part of the public contract, and they can
reveal which workspace ids exist: a 403 for an existing workspace and a 404 for a random id tells
an outsider that the first one is real (MUC-01). What does each kind of refusal answer, and how is
it guaranteed for every endpoint, including future ones?

## Decision Drivers

- An outsider must not learn whether a workspace exists.
- A member must get a useful error when their role is insufficient.
- Failure to resolve a membership must deny access, never grant it.
- Endpoints added later must not be able to skip the rule unnoticed.

## Considered Options

1. 404 for non-members, deleted or unknown workspaces; 403 for an insufficient role.
2. 403 for every refusal.
3. 404 for every refusal, including an insufficient role.

## Decision Outcome

Chosen option: **1** (DEC-015).

- **Resolution:** `WorkspaceAuthorizationInterceptor` resolves the caller's membership through
  `MembershipLookup` on every request, without caching (BR-004), for every handler annotated
  with `@RequiresWorkspaceRole`. The route variable is always `{workspaceId}`.
- **404 `not-found`, identical in every case:** no membership, a membership of another workspace,
  a deleted workspace (BR-012), a malformed id, an unknown stored role.
- **403 `insufficient-role`:** a member whose role is below the handler's minimum. Members already
  know that the workspace exists, so the 403 reveals nothing new.
- **503 `service-unavailable`:** memberships cannot be read, or there is no lookup; fail closed.
- **Guarantee for every endpoint:** a registry of workspace endpoints drives three suites, and a
  build gate fails when a handler under `/api/v1/workspaces/{…}` is not registered (R7), uses
  another variable name, or lacks `@RequiresWorkspaceRole` with the registered role (FF-05).

### Consequences

- Good: the existence of a workspace is never revealed to outsiders, whatever the endpoint.
- Good: members get a precise reason when their role falls short.
- Good: a new endpoint cannot ship without isolation and role coverage; the build fails first.
- Bad: a deleted workspace answers 404 even to its former members, who may expect a clearer
  message; accepted, because BR-012 treats it as non-existent for everyone.
- Neutral: one indexed membership query per workspace request.

### Confirmation

- `WorkspaceAuthorizationInterceptorTest`: one test per branch (404, 403, 503, 401, malformed id,
  missing variable).
- `WorkspaceMembershipLookupTest`: unknown stored role and storage failures fail closed.
- `IsolationSuiteIT`: for every registered endpoint, a non-member and the OWNER of another
  workspace get the same response as a random id.
- `RoleMatrixIT`: for every registered endpoint and role, 403 below the minimum, success from it.
- `WorkspaceEndpointRulesIT`: R7 and FF-05 over the real handler mappings.
- `WorkspaceMembersIT`: a removed member is rejected on the very next request.

## Pros and Cons of the Options

### 404 for non-members, 403 for an insufficient role

- Good: no existence leak; actionable errors for members.
- Bad: two codes to keep consistent; enforced by the interceptor and the suites.

### 403 for every refusal

- Good: simple.
- Bad: an outsider can enumerate workspace ids by comparing a 403 with the 404 of a missing route.

### 404 for every refusal

- Good: maximal opacity.
- Bad: members cannot tell a missing permission from a missing resource; contradicts REQ-FUNC-024.