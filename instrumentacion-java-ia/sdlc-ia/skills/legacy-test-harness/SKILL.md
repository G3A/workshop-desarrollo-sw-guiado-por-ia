---
name: legacy-test-harness
description: Condition a legacy repository of any stack/architecture and grow real, maintainable tests across five layers — unit/collaboration, contract, acceptance, performance, security — targeting code that already ships in production, never a self-contained walking-skeleton. Censuses the actors and maps seams first, Feathers-style; a seam that would require a production edit is proposed as a separate issue for the user to file, never applied inline. Runs in plan mode and writes nothing until you approve. Stack-agnostic. Invoke with `/sdlc-ia:legacy-test-harness [path] [layer,...]`.
model: opus
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

⚠️ **Mandatory — first steps:**

1. Call `EnterPlanMode`. Phases 1 to 5 run inside it: nothing versionable is written while the
   ground is diagnosed and the plan is agreed. Diagnostics that leave build output (`mvn compile`,
   `npm ci`, running the existing suite) **do** run there — `target/` and `node_modules/` are not
   versionable.
2. Do not leave plan mode and do not write a single test until the user approves the Phase 5 plan
   explicitly (Phase 6).
3. The default policy is **zero production changes**. Every seam is proposed; none is applied
   without approval.
4. On Windows, activate the `windows-powershell` skill — if the session offers it — before the
   first non-trivial shell command. Every command this skill runs has to work the same in
   PowerShell 5.1, PowerShell 7 and bash.

## Philosophy

- **Map before you touch.** A seam map built without running anything is cheap to be wrong about; a
  production edit is not.
- **A census, not a sample.** "The relevant actors" is how a run reports success having covered 7%
  of the repository. Enumerate all of them; deciding one needs no tests is a recorded exclusion,
  not an actor you never looked at.
- **One tranche at a time.** Legacy repos are large by definition — generating every layer across
  the whole repo in one pass produces a diff nobody can review.
- **Every proposed seam carries its net.** A seam proposed without a characterization test pinning
  current behavior is a blind refactor handed to whoever picks up the issue.
- **A layer generated is a layer proven.** Every test this skill writes must run, and must fail if
  the production code it targets is reverted — the reality gate in Phase 7 is not optional.
- **Progressive disclosure.** Load a reference when its phase arrives, and within `test-layers.md`
  only the section for a layer in scope for this run.
- **Never commit.** Leave the batch for the user to review, same as every other skill in this
  plugin.

## References

Read on demand, at the phase named — `references/` next to this file.

| Phase | File |
|---|---|
| 2 | `preflight.md` |
| 3 | `seam-mapping.md` · `census-and-tranche.md` |
| 4 | `test-layers.md` |
| 5 and 6 | `deliverable-and-writing.md` |
| 7 | `reality-gate.md` |

## Phase 1 — Fingerprint

Detect the stack(s), build tool, and any test directory/framework already in use (JUnit,
Vitest/Jest/Karma, pytest, ...). There may be several in one monorepo — treat each.

Pick the test framework **per stack**, defaulting to the current major of the stack's standard one,
and keep the repo's own standard instead wherever a **live** suite already uses another. Check that
the test runner's version can actually discover the framework's version — an old Surefire silently
running zero JUnit tests is a false green that survives all the way to the gate.

If a real test strategy already exists, this is an **incremental** run: read the census appendix of
the previous run's ADR to know what is already covered, refresh it against current code, and
generate only what is missing. Never duplicate tests, never regenerate what is already green.

## Phase 2 — Preflight: does the ground run?

Still diagnosis. Verify the terrain **executes** before planning anything on top of it: the runtime
the build demands, a compilable baseline, the existing suite and its baseline of failures, a
container runtime, and whether the dependency feed resolves the test dependencies the layers will
need. → `preflight.md`

The result **conditions the Phase 4 menu**: a layer whose prerequisite failed is offered already
marked blocked, with its cause. It is proposed, not promised.

## Phase 3 — Census the actors, map the seams

Build the **exhaustive, numbered census** of actors carrying business logic, and for each one
enumerate its I/O boundaries and classify them 🟢 fakeable today / 🟡 needs a seam / 🔴 irreducible.
Record the characterization net for every 🟡 and 🔴, and the two-speed split for the stack.
→ `seam-mapping.md`, `census-and-tranche.md`

⚠️ **A collaborator in an injected field is 🟢, not 🟡** — the test populates it by reflection, with
production untouched. Classifying field injection as needing a seam is the single mistake that
leaves whole layers of a dependency-injected legacy codebase without real tests.

🚫 **STOP — present the map to the user** (the boundary table with its colors, the proposed seams,
the two-speed split) before going on. This is the heart of "least impact": the user sees exactly
what would have to be touched, and approves.

## Phase 4 — Scope the tranche and choose the layers

Offer the five layers, with the environment-blocked ones already marked and their cause. Agree a
**tranche** if the census exceeds roughly 30 🟢 actors — and agree, in the same breath, that the
Phase 7 gate measures against the tranche. Warn about the dependencies between layers.
→ `test-layers.md`, `census-and-tranche.md`

Never default to "all five layers, whole repo". Ask with `AskUserQuestion`.

🚫 **STOP — wait for the user's selection.**

## Phase 5 — Consolidate the deliverable (still in plan mode)

Prepare, without writing: the test-strategy ADR (with the census and the inventory of doubles as
appendices), the root-by-type layout, the real tests per layer with their floors, the
characterization nets, the list of seams to approve, and the pipeline gates.
→ `deliverable-and-writing.md`

## Phase 6 — Approval and writing

🚫 **STOP — write nothing until the user explicitly approves the Phase 5 plan.**

Only after a yes: `ExitPlanMode`, then write **only** in test paths, pipeline config and the build
file's test surface — never production. Generate in **batches** of 3 to 5 actors, each batch
compiled and run before the next, with a per-actor retry budget of two retries before the actor is
marked blocked. ⚠️ Parallel subagents **only** with isolated worktrees.
→ `deliverable-and-writing.md`

## Phase 7 — Reality gate — mandatory

Validate that what was written tests **real code**. A self-contained skeleton always compiles and
always passes: that is the false green this phase exists to catch. Run the fast suite twice (the
second in random order), classify every generated file real or skeleton, apply the criteria table,
measure census coverage, and — if the stack allows it cheaply — audit with mutation testing.
→ `reality-gate.md`

If the gate fails, go back to Phase 5 for the deficient layer. Do not close, and do not report that
layer as generated.

## Phase 8 — Report and close

Report: the repo and stack, the preflight result, the census (covered / excluded / pending-seam /
blocked / pending, against the agreed yardstick), the tranche, the seam map, the layers generated,
**partial** or blocked, the Phase 7 result, where the ADR landed, and the pipeline gates with the
result of their first run.

Report, separately, every seam that requires a production edit — a candidate issue title plus the
one-line reason, and whether its characterization net was generated or specified. Do not open the
issue yourself unless asked; that decision belongs to the user (or to `github-plan-build`, if this
run feeds one). Hand over the exact command so filing costs one paste:
`gh issue create --title "Costura: <what>" --label deuda-tecnica` — the title prefix and label are
the convention this monorepo's process viewer (`proceso-operacional-con-ia`, node `bi2`) already
uses, so the backlog stays searchable by one label.

End with "Zero production changes" and the suggested next step.

## Rules

- Do NOT skip plan mode, or write anything before the Phase 6 approval.
- Do NOT generate a test that doesn't exercise real production code.
- Do NOT edit production code to cut a seam — propose it, file it, stop.
- Do NOT sample the census, or measure the gate against anything but the agreed yardstick.
- Do NOT propose a seam without its characterization net.
- Do NOT report a layer as done before the reality gate (Phase 7) confirms it.
- Do NOT commit.
