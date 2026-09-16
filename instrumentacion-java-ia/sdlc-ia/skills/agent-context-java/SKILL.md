---
name: agent-context-java
description: Generate a documentation pack for a Java/Spring repository so AI coding agents can reason about it — AGENTS.md, REVIEW.md and a PR template for the human review layer, an optional EXPERIMENTS.md holding the team's written agreement on what may fail, optional visual intent read from the repo when it has a UI (docs/design-tokens.md and COMPONENTS.md, never proposing a value), architecture, ADRs, data model, infrastructure, plus a `docs/java.md` deep-dive covering the Maven/Gradle module graph, JDK target, Spring DI, JPA/Hibernate or Spring Data persistence, Spring profiles & config, Spring Modulith module boundaries, quality gates, and CI. Output docs default to Spanish; pass `en` for English. Invoke with `/sdlc-ia:agent-context-java` (or `/sdlc-ia:agent-context-java en`).
disable-model-invocation: true
---

# agent-context-java — Bootstrap Java Repository Context

You are generating a **context pack**: a small, cross-linked set of Markdown docs that makes an
unfamiliar Java/Spring repository legible to an AI coding agent. Two halves, one run: the **base
pack** (`AGENTS.md`, `CLAUDE.md`, `docs/` — business, architecture, data model, infrastructure,
ADRs) and the **Java deep-dive** (`docs/java.md` — module graph, JDK target, dependency
management, DI, persistence, config & profiles, build/run/test, Spring Modulith boundaries,
quality gates, web/API surface, packaging, CI).

You MUST NOT write application code, install dependencies, or run destructive commands. Your only
outputs are Markdown files at the project root, under `docs/`, and — for the three files an
external reader loads — at the repository root. Phase 1a resolves which is which.

## Philosophy (hold these in mind throughout)

- **AGENTS.md is a table of contents, not an encyclopedia.** Keep it under ~80 lines.
- **The repository is the system of record.** Anything not in the repo is invisible to the agent.
- **Context is a scarce resource.** Every line in every doc must earn its place. A deleted section
  beats a section full of TODOs.
- **Progressive disclosure.** AGENTS.md points to specialized docs; each specialized doc delegates
  further.
- **TODOs over fabrication.** Never invent a framework version, dependency version, or schema
  detail.
- **No application code.** This skill documents; it does not build.

## Output language

Parse `$ARGUMENTS` for a language tag:

- `en` (or `english`) → generate docs in English, from `templates/en/`.
- `es` (or `español`/`spanish`), or empty → generate docs in Spanish, from `templates/es/`. This
  is the default: this plugin's primary audience is a Spanish-speaking codebase and team.
- Augment mode overrides the default: if Phase 1b finds existing docs, match *their* language
  instead of `$ARGUMENTS`'s default — don't hand a Spanish-speaking repo an English `docs/java.md`
  just because no tag was passed, or vice versa. An explicit `$ARGUMENTS` tag still wins over
  augment-mode detection.

The skill's own instructions (this file) stay in English regardless, matching the rest of this
plugin. Whichever language is resolved, use it for every doc, every placeholder value you write,
the claims ledger (`references/claim-validation.md`), **and every user-facing message during the
run** — interview questions and `AskUserQuestion` labels/options (Phase 2), the augment-mode
report, the commit-message suggestion and any other reminder (Phase 6). Question text quoted
elsewhere in this file is illustrative and written in English for the skill author; phrase it in
the resolved language when you actually ask the user, don't quote it verbatim in the wrong
language.

---

## Phase 1 — Discover (silent)

Do this without talking to the user. Use Glob, Grep, and Read, plus the read-only `git rev-parse`
/ `git ls-files` / `git check-ignore` queries 1a needs.

### 1a. Confirm this is a Java repo, and resolve the two roots

Look for `pom.xml`, `build.gradle`, `build.gradle.kts`, `settings.gradle`(`.kts`), `mvnw`, or
`gradlew`. If nothing matches, stop and tell the user this skill only applies to Java
repositories. Write no files.

The directory holding that build file is the **project root**. It is not always the **repository
root**: in a monorepo the Java project may live in a subfolder, and a file written to the wrong one
can be read by nobody while the run still reports success. Resolve it with one command, in the
project directory:

```
git rev-parse --show-prefix
```

Empty → the two roots are the same and nothing below changes. Non-empty (e.g.
`base-conocimiento/`) → the project is in a subfolder, and that value is also the relative prefix
for cross-root links. **Never compare the two paths as strings** — `--show-toplevel` returns
forward slashes and would differ from the working directory on every Windows repo, including when
the project *is* the root.

Carry the result into Phases 3, 4 and 6, and report it in Phase 2. The guards this needs (no
repository, a project not tracked by the repository it sits in), which files go to which root, and
why: **`references/monorepo-roots.md`**.

### 1b. Detect prior context → augment mode

Switch to **augment mode** if any of these exist **at the project root**: `AGENTS.md`/`CLAUDE.md`, a
`docs/` with `.md` files, `ARCHITECTURE.md`, `ADR/`/`adrs/`/`decisions/`. Read what exists, report
it in Phase 2, create only **missing** docs — never overwrite. A pre-existing `docs/` tree isn't
necessarily yours (many repos ship their own architecture notes, DB dumps) — cross-link it from
`docs/java.md` and AGENTS.md instead of editing it. If those docs are in one language, that
overrides the output-language default — see "Output language" above.

When the roots differ, also read `REVIEW.md`, `.github/pull_request_template.md` and
`EXPERIMENTS.md` **at the repository root** — but only to decide "append, don't overwrite". They
**never** switch augment mode on: otherwise a monorepo whose root holds `AGENTS.md` and `docs/`
would put a brand-new Java subproject into augment mode and refuse to create its own `AGENTS.md`.
A `REVIEW.md` found in the project folder is an **orphan** from an older run — nobody loads it;
report it in Phase 6, never append to it.

### 1c. Deep Java discovery

Run the full checklist in `references/java-inspection.md` (module graph through hotspots). It's
conditional: inspect only what the repo actually signals, and carry that into the doc — delete
`docs/java.md` sections that don't apply. Read real files; where a fact isn't readable, leave a
TODO, don't guess.

### 1d. Adjacent signals

A Java repo is rarely only Java. Glob the signal table in `references/java-inspection.md`
("Adjacent signals") — it feeds `architecture.md` and `infrastructure.md`.

### 1e. Read the README

Seed the one-line project summary from it. Don't copy large chunks.

### 1f. Scan for obvious domain cues

Grep `@Entity`, `@Table`, `@Document`, and repository interfaces for dominant domain nouns
(`Order`, `Invoice`, `Patient`). Use only as Phase 2 prompts — don't hallucinate.

### 1g. Visual intent — only if there is a UI

Run `references/visual-intent.md`: section 1 decides whether the repository has a user interface;
if it does, sections 2 and 4 collect the tokens and components it **already has**. No UI → keep the
list of signals checked for the report; the visual-intent docs are neither offered nor written.

---

## Phase 2 — Interview

**Around ten questions is the norm; more is fine when the repo left a load-bearing gap.** Skip
rule is absolute: **never ask what Phase 1 already read.** Three questions on a well-documented
repo, twelve on a bare legacy codebase — both correct.

`AskUserQuestion` caps at 4 questions/4 options per call, so the structured set needs two
batched calls. Long-form answers don't fit it — ask those in plain chat.

### 2a. Batch A — scope and disambiguation (one `AskUserQuestion`)

1. **Optional docs** — "Generate also `target-user.md`, visual intent and/or `EXPERIMENTS.md`?"
   (`multiSelect`). **Visual intent** — `docs/design.md` + `docs/design-tokens.md` +
   `COMPONENTS.md`, one option because `design.md` links to the other two — is offered **only if 1g
   found a UI**; otherwise drop the option and say in the report that no UI was found and what was
   checked. For `EXPERIMENTS.md`, say what it is and what it is not in the option itself:
   *the written agreement about what the team may try with the agent and what happens when it goes
   wrong — the form only; **you fill in the content**, and the skill will not answer it for you.*
2. **Augment-mode confirmation** — only if Phase 1b found existing docs: list them, then
   `Yes (augment only)` / `Overwrite matching docs` / `Cancel`.
3. **Phase-1 ambiguity** — the one thing discovery couldn't settle: usually the persistence
   framework (JPA annotations + a Spring Data JDBC repository coexisting) or the build tool
   (`pom.xml` and `build.gradle` both present). Offer candidates **you actually read**.

### 2b. Batch B — facts that live outside the repo (second `AskUserQuestion`)

4. **Production deployment target** — rarely readable from source, needed by
   `infrastructure.md`: `Kubernetes / managed container` / `VM or on-prem app server (WildFly,
   Tomcat)` / `PaaS` / `Other`.
5. **Production secrets source** — `Vault / cloud secrets manager` / `Env vars` / `Spring Cloud
   Config` / `Other`.
6. **Auth / identity model** — only if ambiguous from the dependencies: `Spring Security +
   OAuth2/OIDC` / `Spring Security + JWT (self-issued)` / `Jakarta security (JAAS)` / `Other`.
7. **Path to production** — `CI deploys on merge to main` / `Tag / release triggers deploy` /
   `Manual` / `Other`. The pipeline often shows the build but not the promotion path.

### 2c. Free-text answers — ask in plain chat

8. **Business context** — "In one or two sentences: what does this product do, who pays for it?"
9. **Non-obvious rules** — "Up to 3 invariants/gotchas an agent must know that aren't enforced
    by linters or tests, e.g. *'`web` must not import `recuperacion` directly, only through the
    facade', 'never bypass the tenant query filter', 'run Flyway before starting the app', 'don't
    touch the legacy `legado` module'*. Reply 'skip' if none."

### 2d. Conditional extras — ask only when the repo left the gap

10. **Test expectations** — only if coverage looked thin: unit only / integration required /
    end-to-end?
11. **Ownership / escalation** — only with no `CODEOWNERS` and no obvious maintainer.

Do not proceed to Phase 3 until the interview is complete.

---

## Phase 3 — Draft

For each doc, read `templates/<lang>/<doc>.md.template` (`<lang>` resolved above), substitute placeholders
(`{{UPPER_SNAKE}}`, declared at the top of each template), write to the target path:

Each path is anchored to one of the two roots from Phase 1a — **the root of whoever reads the
file**. They coincide unless the project is in a subfolder.

**Project root** — the agent reads these, following links, at every level of the hierarchy:

- `AGENTS.md`, `CLAUDE.md` — see Phase 4
- `docs/business.md`, `docs/architecture.md`, `docs/data-model.md`, `docs/infrastructure.md`,
  `docs/java.md`
- `docs/adrs/README.md` + `docs/adrs/adr-template.md` + `docs/adrs/0001-<slug>.md` (1–3 seed ADRs)
- `docs/target-user.md` (only if opted in)
- `docs/design.md`, `docs/design-tokens.md`, `COMPONENTS.md` — only if 1g found a UI and
  the user opted in; see the second exception below

**Repository root** — an external reader loads these, and only looks there:

- `REVIEW.md` — the cloud Code Review service reads it at the repository root, and nowhere else
- `.github/pull_request_template.md` — GitHub looks for it in the repository, never in a subfolder.
  One per repository, ever: if the root already has one linking to `REVIEW.md`, append nothing
- `EXPERIMENTS.md` (only if opted in) — the agreement is the team's, not one folder's; two of them
  in a repository is the failure to avoid. See the exception below

Writing to the repository root is unprompted when it is free, and a question when those files
already belong to someone else — the rule, the guards and the reasoning:
**`references/monorepo-roots.md`**.

Rules: short sentences, sacrifice grammar for clarity. No info for a section →
`<!-- TODO: fill in -->`, don't hallucinate; a whole section that doesn't apply (no UI, no
Modulith, no message broker) → **delete it**, don't pad with TODOs.

**`EXPERIMENTS.md` is the one file where TODOs are the correct output, not a shortfall.** It holds
the team's written agreement about what may fail and what happens when it does — a leadership
decision the repository cannot contain. Everywhere else a TODO means discovery fell short; here it
means **the answer is not in the repository and must not be invented**. Fill in only `<PROJECT>`,
`<INTEGRATION-BRANCH>`, and the "never an experiment" list — which you copy from
`github-plan-build`'s escalation list so the two say the same thing, marked as a starting point.
Leave every other slot open, and say in the report that you did so on purpose: a team's risk
posture invented by a model is the exact hallucination this skill exists to prevent. **Augment mode never clobbers
user content** — fill TODO slots or append a clearly marked subsection, leave the rest alone;
pre-existing docs are read-only, cross-link instead of editing.

**`docs/design-tokens.md` and `COMPONENTS.md` are the second exception.** They record what the
repository already defines; they **never propose a value** — no scale, ramp, palette or colour
roles. A UI with no tokens, or with no component structure, still gets the file, with a TODO and the
list of what was searched: **that TODO is the correct output**. When tokens are defined in more than
one place, never pick a copy or unify values — every source is written and the difference is a
finding. How to read, record, and augment an existing `design.md`: `references/visual-intent.md`.

What each doc must carry from the Java discovery, and the ADR seeds, are in
`references/doc-content-map.md`.

---

## Phase 4 — Wire (AGENTS.md + CLAUDE.md + REVIEW.md)

Generate `AGENTS.md` strictly as a **table of contents** — the section list and what goes in each
is in `references/doc-content-map.md`. Enforce the ~80-line ceiling — move overflow into
`docs/java.md`.

`CLAUDE.md` is one line: `@AGENTS.md`, with a comment explaining that it delegates.

### `REVIEW.md` — a third file, and a separate one on purpose

`AGENTS.md` holds the rules the agent must respect **while generating**; `REVIEW.md` holds the
criteria for **what to look at in a diff that already exists**. They are different files because
they load in different places: the cloud PR-review service reads `REVIEW.md` — at the repository
root, and nowhere else — while the local `/code-review` never reads `REVIEW.md` at all and follows
`CLAUDE.md`, the one line that imports `AGENTS.md`, at every level of the hierarchy. A criterion
that must hold in both goes in `AGENTS.md`; one
that only applies while reviewing goes in `REVIEW.md`. Say this in the report — a team that copies
the same lines into both ends up maintaining neither.

Write it from `templates/<lang>/REVIEW.md.template`, **at the repository root**: six categories,
fifteen items. **The six categories are the method's** (verification layer 4). **The fifteen
concrete items are this template's own wording, not a quotation** — say so when you report, and
invite the team to change them.

When the project is in a subfolder, the template's conditional preamble block explains where the
file lives and which pieces it covers — fill in the folder names, don't redraft the sentence — and
each item that only holds for this project **names its folder inside the item's own text**, not
with a prefix or tag. An item true everywhere says nothing extra. How to scope, what to do with
generic items an earlier run left behind, and when writing to the root becomes a question:
**`references/monorepo-roots.md`**.

Then write `.github/pull_request_template.md` from
`templates/<lang>/pull_request_template.md.template`, **also at the repository root** — GitHub only
looks for it there, so in a subfolder it is dead paper. Its `../REVIEW.md` link needs no
adjustment: relative to `.github/`, it resolves exactly when both files sit at that root. It is
deliberately **short and links to `REVIEW.md` instead of repeating it** — a second copy of the list
drifts from the first within a few sprints. Its six boxes are the categories, not the fifteen
items.

Three rules for this pair:

- **Never make the boxes a CI check.** A workflow that requires them ticked turns judgement into
  paperwork: all six get ticked unread and the record starts lying. They leave a trace of what was
  reviewed; they do not guarantee it.
- **An existing `REVIEW.md` or PR template is never replaced** — augment mode applies here too:
  fill gaps, append a marked section, report what you left alone.
- **Tailor, do not pad.** Add an item only when discovery justifies it (a migrations item if the
  repo has Flyway or Liquibase), and mark it as added. Fifteen items people read beat twenty-five
  they skim.

---

## Phase 5 — Validate claims (Claimify-inspired)

Generated docs hallucinate. Before finishing, surface the load-bearing factual claims you wrote
and confirm the uncertain ones with the user. Adapted from Microsoft Research's **Claimify** —
atomic, self-contained, verifiable claims, **flag ambiguity instead of guessing**. Follow
`references/claim-validation.md` in full: select the load-bearing claims, atomize and tag each
with its source/confidence, flag ambiguity, verify with the user, apply corrections, then persist
the ledger to `docs/claims-ledger.md`.

---

## Phase 6 — Verify

1. Print a tree of files written (or augmented) — in the resolved language.
2. Check every link in `AGENTS.md`, `docs/java.md` and, when generated, `docs/design.md`,
   `docs/design-tokens.md` and `COMPONENTS.md` resolves to a file that exists (use Read) — the last
   three link to each other across the root and `docs/`.
   Include the PR template's link to `REVIEW.md`: a relative path that does not resolve is the
   failure mode of this pair, and it only shows up months later, when someone clicks it mid-review.
3. Remind the user, in the resolved language, to commit — suggest a commit message matching that
   language (e.g. `docs: bootstrap Java context pack for AI coding agents` in English,
   `docs: agrega el paquete de contexto Java para agentes de IA` in Spanish):
   `git add AGENTS.md CLAUDE.md REVIEW.md .github/ docs/` — plus `EXPERIMENTS.md` and `COMPONENTS.md`
   when they were generated — then `git commit -m "<message>"` (two
   commands, no `&&`, so it works in Windows PowerShell 5.1 too);
   fill `<!-- TODO -->` markers, review the ADRs, skim `docs/claims-ledger.md` for anything
   unverified; if quality gates were absent, consider Checkstyle/Spotless + an arch-linting test
   (ArchUnit, or `ApplicationModules.verify()` if modules exist); re-run
   `/sdlc-ia:agent-context-java` later — it augments, never overwrites.

---

## Reference

- `references/java-inspection.md` + `references/java-inspection-2.md` — the full Java discovery
  checklist (Phase 1c).
- `references/visual-intent.md` — whether there is a UI, and how tokens and components are read
  from the repo (Phase 1g, Phase 3).
- `references/doc-content-map.md` — what each doc carries, and the `AGENTS.md` section list
  (Phase 3, Phase 4).
- `references/claim-validation.md` — the Claimify-inspired claim-validation procedure (Phase 5).
- `templates/es/` / `templates/en/` — the doc skeletons, one set per output language.

## Rules

- Do NOT write application code.
- Do NOT overwrite existing docs without explicit user opt-in; enrich by filling TODOs or
  appending clearly marked sections.
- Do NOT fabricate framework or dependency versions, providers, endpoint names, or schema you
  haven't read.
- Do NOT answer `EXPERIMENTS.md` for the team. Its TODOs are the deliverable, not a shortfall.
- Do NOT propose design values in `docs/design-tokens.md` or `COMPONENTS.md`. Read them or leave the TODO.
- Do NOT pick one copy of a token defined in several places, and do NOT unify values: report the
  divergence as a finding.
- DO leave `<!-- TODO -->` markers where human input is needed, and delete sections that don't
  apply rather than padding them.
- DO keep every doc focused: each has one job, delegated from AGENTS.md.
