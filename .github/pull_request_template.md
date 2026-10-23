## Summary

<!-- What this block delivers and why. -->

## Requirements

<!-- Requirements closed by this PR, e.g. `Verifies: REQ-FUNC-011, REQ-FUNC-012`. Write "None" for build or tooling changes. -->

## Decisions

<!-- ADRs created or updated, and any deviation from the SDD. Write "None" if there are none. -->

## How to try it

<!-- Test class names or Bruno requests that demonstrate the change. -->

## Checklist

- [ ] Every requirement listed above has at least one test annotated with `@Verifies`
- [ ] Errors use the problem-type catalogue; new types are added to `docs/api/problem-types.md`
- [ ] Security-relevant actions write an audit entry
- [ ] No secret, token or e-mail appears in logs
- [ ] New code in `shared` is used by at least two modules and contains no business rule
- [ ] `./mvnw verify` passes locally
- [ ] ADRs and documents affected by this change are updated