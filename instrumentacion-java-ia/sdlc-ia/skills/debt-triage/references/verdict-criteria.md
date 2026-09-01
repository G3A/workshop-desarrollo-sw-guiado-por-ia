# Verdict criteria — worked examples

Each verdict needs one sentence of reasoning tied to the actual code, not the rule's generic
description. These are illustrative, not exhaustive — the same rule can land on a different verdict
in a different call site.

## REAL

- **Null-dereference warning on a value that comes from an untyped external call** (an HTTP
  response, a JDBC result set, `JSON.parse`). The rule is right: nothing upstream guarantees
  non-null. Fix: a null check or an `Optional`/equivalent, scoped to that call site only.
- **Unused import/variable left after a refactor.** No caller relies on it — delete it.
- **SQL built by string concatenation with a request-derived value.** Real regardless of whether an
  exploit is likely today — fix with a parameterized query.

## FALSE POSITIVE

- **"Unused" field that a framework populates by reflection** (a Spring `@Autowired` field, a
  Jackson-deserialized DTO field, an Angular `@Input()`). The linter can't see the framework's own
  wiring. Suppress with a comment naming the framework mechanism, not just "false positive".
- **"Missing null check" on a value the type system (or an assertion two lines above) already
  guarantees non-null.** Point at the guarantee in the suppression comment.
- **Cyclomatic-complexity warning on a generated file** (a parser, a protobuf stub). Suppress at the
  file/glob level, not per finding — and confirm the file really is generated before doing so.

## ACCEPTED RISK

- **Deprecated API call inside a module already scheduled for removal** (tracked in an open issue
  or a `TODO` with a date). Fixing it now is churn on code with a known expiration; link the
  tracking issue in the suppression.
- **A dependency with a known low-severity CVE that has no available patch yet**, in a code path
  with no external input reaching it. Note the CVE id and why the exposure is judged low, and set a
  reminder to re-check when a patch ships — don't suppress silently forever.
- **A style/complexity rule violated by code that mirrors an external spec** (a state machine that
  matches a protocol diagram 1:1) where splitting it up would make it harder, not easier, to verify
  against that spec.

If you cannot write the one-sentence reason without guessing, the finding is not FALSE POSITIVE or
ACCEPTED RISK yet — treat it as REAL-but-uncertain and flag it for a human instead.
