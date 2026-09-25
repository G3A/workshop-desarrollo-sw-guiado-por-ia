# Census, tranche and the two-speed split

Phase 3 builds the census; Phase 4 agrees the tranche. Together they are the **yardstick** the
Phase 7 gate measures totality against — without them, "layer generated" is a claim with no
denominator, and a run can report success having covered 7% of the repository.

## The census

Enumerate **every** actor carrying business logic in the target module(s) — classes named
`*Service`/`*Logic`/`*Handler`, use-case classes, components with behavior, the modules a router
dispatches to. Number them.

**Not "the relevant ones": all of them.** Sampling here is exactly what lets a gate pass later while
most of the repository stays untested. Deciding an actor doesn't need tests is a legitimate outcome
— but it is recorded as `excluded` with a justification, which is a different thing from never
having looked.

Utilities with no branching (formatting, rounding, constants) are actors too; they just tend to end
up `excluded — trivial`.

For each actor, enumerate its **I/O boundaries** — HTTP client, database, queue, filesystem, clock,
environment, native call — and classify each one 🟢/🟡/🔴 per `seam-mapping.md`.

## The five states

At close, every actor in the census carries exactly one:

| State | Meaning |
|---|---|
| `covered` | has real tests against real production code |
| `excluded` | deliberately not tested, **with the justification written down** |
| `pending-seam` | 🟡: needs a proposed seam, and carries its characterization net |
| `blocked` | 🔴, or an environment block from Phase 2, with the diagnosis |
| `pending` | outside the agreed tranche — only exists when a tranche was agreed |

A characterization net does **not** turn `pending-seam` into `covered`. The net pins current
behavior so a future refactor is safe; it does not test the behavior the actor should have.

`covered` and `excluded` are the only two states that mean "this actor needs nothing further from
this run". The other three are each a different kind of debt, and the closing report counts them
separately so none of them hides inside a percentage.

## The census appendix

The census goes into the Phase 5 ADR as an appendix, grouped by package/directory, one row per
actor: `| Actor | State | Note |`. Keep the state vocabulary above literally — this appendix is
what the **incremental mode** reads on a later run to know what's already done, and a free-prose
state is a state the next run can't act on.

`covered`, `pending-seam` and `blocked` always get their own row: each one moves the gate's result.
In a large census, `excluded` and `pending` may be summarized in prose (count per package + reason +
priority for the next run), because they are the only two states outside the gate's yardstick —
they count in neither the numerator nor the denominator, so compacting them cannot move the
percentage.

## The tranche

**Mandatory to agree once the census exceeds roughly 30 actors classified 🟢.**

Covering hundreds of actors in one run is not realistic, and promising it produces a dishonest
close. Propose a prioritized subset — the domain rules and the highest-business-value actors,
typically 15 to 30 — and agree explicitly that **the Phase 7 gate measures against the tranche,
not against the whole census**.

The rest of the census stays in the appendix as `pending`. That is the honest tail, and it is what
makes the run **resumable**: each later invocation is an incremental run that reads the appendix,
refreshes the census against current code, and takes the next tranche until the census is exhausted.

Record the agreed tranche in the ADR. **With no explicit tranche, the yardstick stays the full
census** — the default is the strict one, so that forgetting to agree a tranche cannot silently
shrink what the gate checks.

## The two-speed split

Define, per stack, which suite runs **fast with no real I/O** and which runs **slow against real
infrastructure**. This is what keeps the fast suite usable on every commit while the slow one stays
opt-in:

| Stack | Fast | Slow (opt-in) |
|---|---|---|
| Maven | `*Test` via Surefire | `*IT` via Failsafe, behind a profile such as `-Pit` |
| Gradle | the `test` task | a separate `integrationTest` task |
| Node / TS | unit runner (Vitest, Jest) | Playwright/Cypress e2e in its own script |
| AngularJS | Karma unit | e2e in its own runner |
| Python | `pytest -m "not integration"` | `pytest -m integration` |

Two things the gate checks in Phase 7, so decide them here: the fast suite must run with **no
container runtime present**, and the slow tests must **skip cleanly** in that case rather than fail.
A slow test that fails when the infrastructure is absent turns the fast suite red for everyone who
doesn't have Docker running, and the usual reaction to that is to stop running the suite.
