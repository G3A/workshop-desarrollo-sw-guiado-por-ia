# The build loop — issue to green PR

This is the body of the `github-plan-build` skill. The calling `SKILL.md` reads the
issue and supplies the **bindings**; everything below is tracker-agnostic and runs the
same way whatever tracker the issue came from.

> This procedure is intentionally duplicated across every ticket-to-PR delivery skill
> (skills ship independently, even across plugins). If you change it here, mirror the
> change in every other delivery skill's copy of this file.

**Bindings** the calling skill defines, referenced below by name:

| Binding | What it names |
|---|---|
| `TICKET` | the work item / issue you are implementing |
| `STATUS→IN-PROGRESS` | how to move `TICKET` to its in-progress state |
| `STATUS→IN-REVIEW` | how to move `TICKET` to its in-review state |
| `COMMENT` | how to post a comment on `TICKET` |
| `BRANCH` | the feature branch name |
| `LINK-TOKEN` | the string that makes the tracker attach a commit to `TICKET` |
| `OPEN-PR` | the command that opens the pull request |
| `CI` | how to read check status, failing logs, and rerun a job |
| `PR-COMMENTS` | how to read and reply to review comments |

Run steps A → J in order: **A–E here, F–J in `references/build-loop-execute.md`**. Only Step E
pauses for the user; everything else is **act and self-verify.** See **Escalation** at the end of
`build-loop-execute.md` for the complete list of things that stop you.

---

## Step A — Grill the user

A ticket is a pointer, not a specification. Before you explore, close the design
questions the ticket left open — a wrong assumption discovered at Step F (implementation, in
`build-loop-execute.md`) costs an implementation; discovered here it costs one question.

**Ask only what nobody has answered yet.** Read `TICKET`, its comments, its parent and
subissues, and the repo's `AGENTS.md` / `CLAUDE.md` / `docs/` pack **first**. A
question whose answer is already written down is noise, and it teaches the user that
your questions aren't worth reading.

Use `AskUserQuestion`: **at most 4 questions per call, 2–4 options each.** Prefer a
single round; two at the absolute most. Put your recommended option first and mark it
`(Recommended)` — you have read the code, so have an opinion.

Sweep these categories and ask about the ones that are genuinely open **and would
change the code**:

- **Scope boundary** — what the ticket implies but never states, and what is
  explicitly out.
- **Data model** — new fields, nullability, defaults, and whether this needs a
  migration.
- **Contract** — the API/CLI/event shape, and whether breaking existing callers is
  acceptable.
- **Failure behavior** — what happens on invalid input, a missing record, a timeout,
  a partial write. Silent skip, error, or retry?
- **Auth and permissions** — who may do this, and what a caller without the right
  sees.
- **Scale** — the expected volume, and whether the obvious implementation survives it.
- **User-visible surface** — copy, states, empty and error cases.
- **Rollout** — behind a flag or straight on; migration of existing data.
- **Test depth** — unit only, or an integration test against the real dependency.

Record the outcome in two explicit lists you carry into the plan:

- **Decisions** — what the user answered. Quote them; do not paraphrase into
  something looser.
- **Assumptions** — what you settled yourself because it was too minor to ask.
  Assumptions are exactly what the Step E checkpoint exists to catch, so write them
  down even when you are confident.

Step A is **not** skippable. The `skip-checkpoint` argument governs Step E only.

## Step B — Explore

1. **Conventions first.** Read `AGENTS.md`, `CLAUDE.md`, or a `docs/` context pack —
   that is the fastest path to this repo's patterns, commands, and non-obvious rules.
   If none exists, infer the conventions from the code as you go and treat the
   inference as provisional.

2. **Do not assume an architecture.** Clean Architecture, hexagonal, MVC, layered,
   modular monolith — read what this repo actually does and follow it. Never plan
   against a pattern the repo does not use, and never propose adopting one; that is a
   separate conversation, not a side effect of a ticket.

3. **Fan out `Explore` subagents along the repo's own seams**, all in one message so
   they run concurrently. Pick the partition the codebase already has — modules,
   packages, services, bounded contexts, whatever it is — and scope each agent to the
   area the feature touches. Ask each for the files that exist, the exact symbols
   involved, and any edge case visible in its area (an unguarded parse, a missing
   uniqueness check, a swallowed error). Tell each one to **read, not judge** — no
   plans, no fixes. Their combined output is a map, not an opinion.

4. Synthesize one short map yourself. Where two subagents disagree about the same
   file, open it and settle it — do not average their claims. If the feature is a
   single-file change, skip the fan-out; several agents to read one function is waste,
   and saying so out loud is part of the discipline.

## Step C — Draft the plan

Write a step-by-step plan: **small steps**, each naming the exact file(s) and its own
verification, expressed in **the repo's own commands** (from `AGENTS.md`'s "Commands"
section, the `Makefile`, `package.json` scripts, or whatever the repo uses). The
**first implementation step is a failing test** that proves the behavior is missing.

The plan must also carry, verbatim: the **Decisions** and **Assumptions** from Step A,
the dependencies and risks, and what you deliberately left out of scope.

## Step D — Adversarial review

Critique the draft **before** any code. Fan out **three `general-purpose` subagents in
one message**, each with a *different lens* rather than three copies of the same
reviewer — redundancy catches less than diversity does. Pass each the ticket, the
Decisions/Assumptions, and the full draft plan:

> **Conventions lens.** Critique this plan against this repo's conventions as
> documented in `AGENTS.md`/`docs/` (or the patterns observed while exploring):
> error-handling style, layering and dependency rules, where validation belongs,
> naming, and any non-obvious rule the docs call out. Flag any step that would violate
> an established pattern or put logic in the wrong place. Judge against what this repo
> does, not against an architecture you would prefer.
>
> **Correctness lens.** Hunt for unhandled edge cases and wrong behavior: inputs the
> plan never validates, states it never reaches, tests that would pass while the bug
> survives. For each, give the concrete input and the wrong result.
>
> **Scope lens.** Find what is missing and what does not belong: steps absent from the
> plan that the acceptance criteria demand, steps present that no criterion asks for,
> and anything requiring a migration or a product decision nobody has answered.
> Challenge the Assumptions list specifically — an assumption that should have been a
> question is a finding.

Each returns a short structured list of concrete issues with a suggested fix, and
**writes no code**.

Fold the three critiques into one revised plan. **Judge, don't tally** — a single
reviewer naming a real convention violation outranks two that found nothing, and a
confidently-argued finding that is simply wrong gets dropped with a reason.
Deduplicate where lenses overlap. State plainly what each lens flagged and how you
resolved it, including what you rejected and why. If a critique surfaced a genuine
product-judgment gap that Step A did not close, that is an escalation — ask.

## Step E — Approval checkpoint (conditional)

This is the **one** place the workflow stops for the user. Whether it stops at all
depends on the plan, and the test is concrete, not a feeling.

**Enter plan mode if ANY of these hold:**

- more than ~3 implementation steps, or more than ~3 files touched;
- it changes a public contract (API, CLI, event, exported symbol), a data schema or
  migration, auth/permissions, or a billing/money path;
- the adversarial review left a product-judgment question unresolved;
- the plan rests on **Assumptions** rather than answered **Decisions**;
- it is hard to reverse — deletes data, rewrites history, changes a production
  default;
- the user asked to see a plan.

To enter: call `EnterPlanMode`, present the vetted plan with a short note on what each
review lens flagged and how you resolved it, then `ExitPlanMode` to submit it. If the
session is **already** in plan mode, do not call `EnterPlanMode` again — go straight
to `ExitPlanMode`. Write no code until it is approved.

**Skip plan mode only if ALL of the inverse hold** — small, reversible, no contract or
schema or auth change, no open questions, everything a Decision. Then print the final
plan inline for the record and keep going.

`skip-checkpoint` in the arguments forces the skip for routine tickets, but it never
overrides a user who asked for approval. Either way, **say which branch you took and
why** in one line.

If the tracker's binding table defines a **`PLAN-PERSIST`** step, run it now, once the
plan is approved — before `STATUS→IN-PROGRESS`. Most trackers define nothing here and
this is a no-op; where one does, it decides whether the approved plan gets copied
somewhere durable or stays only the ephemeral record the approval checkpoint already
produced.

Set `STATUS→IN-PROGRESS` here (pre-authorized — do not ask).

**Steps F–J continue in `references/build-loop-execute.md`.**
