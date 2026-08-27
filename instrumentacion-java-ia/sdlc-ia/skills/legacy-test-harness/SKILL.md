---
name: legacy-test-harness
description: Condition a legacy repository of any stack/architecture and grow real, maintainable tests across five layers — unit/collaboration, contract, acceptance, performance, security — targeting code that already ships in production, never a self-contained walking-skeleton. Maps seams first, Feathers-style; a seam that would require a production edit is proposed and filed as a separate issue, never applied inline. Stack-agnostic. Invoke with `/sdlc-ia:legacy-test-harness [path] [layer,...]`.
disable-model-invocation: true
argument-hint: "[path] [layer,...]"
---

# legacy-test-harness — Real Tests on Real Legacy Code

You are conditioning a legacy repository — architecture unknown, tests thin or absent — to grow
**real tests on real production code**, one layer and one tranche at a time. Two rules hold
throughout:

1. **Real code, not a walking skeleton.** The subject of every generated test is a class or
   function that already ships in production. A self-contained scaffold that never touches real
   code does not count as a generated layer.
2. **Zero production changes without approval.** Seams are *proposed*, never applied. A seam that
   can only be cut by editing production code becomes a filed issue, not a silent edit.

## Philosophy

- **Map before you touch.** A seam map built without running anything is cheap to be wrong about; a
  production edit is not.
- **One tranche at a time.** Legacy repos are large by definition — generating every layer across
  the whole repo in one pass produces a diff nobody can review. Scope to a module or a handful of
  classes per run.
- **A layer generated is a layer proven.** Every test this skill writes must run, and must fail if
  the production code it targets is reverted — the reality gate in Phase 5 is not optional.
- **Progressive disclosure by layer.** Load `references/test-layers.md`'s section for a layer only
  once that layer is in scope for this run.
- **Never commit.** Leave the batch for the user to review, same as every other skill in this
  plugin.

## Phase 1 — Fingerprint

Detect the stack(s), build tool, and any test directory/framework already in use (JUnit,
Jest/Jasmine/Karma, pytest, ...). If a real test strategy already exists, this is an
**incremental** run — extend it, don't replace it.

## Phase 2 — Map the seams

Walk the target module(s) with Feathers' seam-finding lens (constructor injection points, static
calls, singletons, `new` inside the method under test) — technique and per-stack patterns in
`references/seam-mapping.md`. Classify each seam:

- **Cuttable from the test** — reflection, a test subclass, a wrapper the test owns. Use it.
- **Requires a production edit** — do NOT edit. Record it for Phase 6 with the one-line reason a
  test can't reach it otherwise.

## Phase 3 — Scope the tranche

Present the seam map and ask, via `AskUserQuestion`, which layer(s) to generate this run and which
module/class subset. Never default to "all five layers, whole repo" — confirm scope explicitly.

## Phase 4 — Generate, per layer

For each chosen layer, follow its section in `references/test-layers.md` — what a real
(non-scaffold) test looks like for that layer, the default framework per stack, and the layer's own
gate. Do not touch production code; where Phase 2 found a seam that needs it, generate the test
around the seam as proposed instead (e.g. via reflection).

## Phase 5 — Reality gate

Before reporting a layer as generated, confirm — per `references/reality-gate.md` — that every new
test targets a class/function under the stack's production source root (not a test-only double),
fails when the production behavior it targets is reverted, and runs green otherwise. A test that
passes unconditionally, or never imports production code, is scaffolding — it does not count.

## Phase 6 — Report and close

Report, per layer generated: files added, what they test, and the reality-gate result. Report,
separately, every seam found that requires a production edit — as a candidate issue title plus a
one-line reason. Do not open the issue yourself unless asked; that decision belongs to the user (or
to `github-plan-build`, if this run feeds one).

## Rules

- Do NOT generate a test that doesn't exercise real production code.
- Do NOT edit production code to cut a seam — propose it, file it, stop.
- Do NOT generate all five layers across a whole repo in a single run — scope the tranche first.
- Do NOT report a layer as done before the reality gate (Phase 5) confirms it.
- Do NOT commit.
