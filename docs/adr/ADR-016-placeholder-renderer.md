# ADR-016: Restricted placeholder renderer for templates

- Status: accepted
- Date: 2026-11-01
- Deciders: Kaulin Mindiola
- Related: REQ-SEC-013, REQ-SEC-014, REQ-FUNC-052, REQ-FUNC-053, REQ-FUNC-054, DEC-040; SDD V09,
  V14-A6; ADR-011

## Context and Problem Statement

Notification templates are plain text with variables (SRS appendix B). In release 1.0 only the
global templates seeded by migration exist (D-01); release 1.1 lets ADMINs customize the
workspace-scope ones (REQ-FUNC-054). Whatever renders them must not let a template run logic,
include other templates or reach values beyond the variables declared for its key (REQ-SEC-013),
and a value such as a workspace name must never be interpreted as template syntax.

Which engine renders templates?

## Decision Drivers

- No logic, sections, partials or expressions: a template is text plus variables.
- Validation that names undeclared variables before a template is stored.
- Values inserted literally, never re-scanned.
- Small, auditable code; no dependency whose features must be switched off.

## Considered Options

1. An in-house single-pass `{{variable}}` renderer with validation against the declared variables.
2. Mustache (JMustache or mustache.java).
3. Thymeleaf or FreeMarker in text mode.

## Decision Outcome

Chosen option: **1**, `PlaceholderRenderer`.

- A placeholder is `{{name}}`, optional spaces inside, the name a letter followed by letters or
  digits.
- `validate(key, subject, body)` rejects every undeclared name and any other `{{` or `}}`
  construct, and lists all violations without quoting the template text.
- `render` replaces each placeholder in one left-to-right pass; inserted values are not scanned
  again, so a workspace named `{{token}}` renders literally; a missing value is an error.
- Rendering happens at send time with the template version pinned at creation (REQ-FUNC-053). The
  result, which may hold a link token, is never logged or returned by the API (REQ-SEC-014).

### Consequences

- Good: the whole attack surface is one regular expression and a loop.
- Good: the same validation will guard workspace customizations in release 1.1.
- Bad: no conditionals or loops; every template variant is a separate template or locale.

### Confirmation

- `PlaceholderRendererTest`: replacement in subject and body, literal insertion of values that look
  like placeholders or contain regular-expression replacement characters, missing values,
  undeclared variables in subject and body, any other double-brace construct, unknown keys, and
  violations that never quote the template.
- `TemplateResolverIT`: every seeded global template passes validation.
- `SecretsInResponsesIT`: with deliveries rendered and sent, no API response contains a link token.

## Pros and Cons of the Options

### In-house renderer

- Good: exactly the features REQ-SEC-013 allows.
- Bad: owned code, though about fifty lines.

### Mustache

- Good: logic-less by reputation, widely known.
- Bad: sections, inverted sections and partials still exist and would have to be rejected.

### Thymeleaf or FreeMarker

- Good: powerful, already common in Spring applications.
- Bad: expression languages are precisely what must not reach ADMIN-editable text.