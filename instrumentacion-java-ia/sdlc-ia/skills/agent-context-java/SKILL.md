---
name: agent-context-java
description: Generate a documentation pack for a Java/Spring repository so AI coding agents can reason about it — AGENTS.md, architecture, ADRs, data model, infrastructure, plus a `docs/java.md` deep-dive covering the Maven/Gradle module graph, JDK target, Spring DI, JPA/Hibernate or Spring Data persistence, Spring profiles & config, Spring Modulith module boundaries, quality gates, and CI. Output docs are in Spanish. Invoke with `/sdlc-ia:agent-context-java`.
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
outputs are Markdown files at the repo root and under `docs/`.

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

Generated docs are always in Spanish (`templates/es/`) — this skill targets a Spanish-speaking
codebase and team. The skill's own instructions (this file) stay in English regardless, matching
the rest of this plugin.

---

## Phase 1 — Discover (silent)

Do this without talking to the user. Use Glob, Grep, and Read.

### 1a. Confirm this is a Java repo

Look for `pom.xml`, `build.gradle`, `build.gradle.kts`, `settings.gradle`(`.kts`), `mvnw`, or
`gradlew`. If nothing matches, stop and tell the user this skill only applies to Java
repositories. Write no files.

### 1b. Detect prior context → augment mode

Switch to **augment mode** if any of these exist: `AGENTS.md`/`CLAUDE.md` at repo root, a
`docs/` with `.md` files, `ARCHITECTURE.md`, `ADR/`/`adrs/`/`decisions/`. Read what exists, report
it in Phase 2, create only **missing** docs — never overwrite. A pre-existing `docs/` tree isn't
necessarily yours (many repos ship their own architecture notes, DB dumps) — cross-link it from
`docs/java.md` and AGENTS.md instead of editing it. If those docs are in one language, match it
in Phase 2's language question.

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

---

## Phase 2 — Interview

**Around ten questions is the norm; more is fine when the repo left a load-bearing gap.** Skip
rule is absolute: **never ask what Phase 1 already read.** Three questions on a well-documented
repo, twelve on a bare legacy codebase — both correct.

`AskUserQuestion` caps at 4 questions/4 options per call, so the structured set needs two
batched calls. Long-form answers don't fit it — ask those in plain chat.

### 2a. Batch A — scope and disambiguation (one `AskUserQuestion`)

1. **Optional docs** — "Generate also `target-user.md` and/or `design.md`?" (`multiSelect`).
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

For each doc, read `templates/es/<doc>.md.template`, substitute placeholders
(`{{UPPER_SNAKE}}`, declared at the top of each template), write to the target path:

- `AGENTS.md`, `CLAUDE.md` (repo root) — see Phase 4
- `docs/business.md`, `docs/architecture.md`, `docs/data-model.md`, `docs/infrastructure.md`,
  `docs/java.md`
- `docs/adrs/README.md` + `docs/adrs/adr-template.md` + `docs/adrs/0001-<slug>.md` (1–3 seed ADRs)
- `docs/target-user.md`, `docs/design.md` (only if opted in)

Rules: short sentences, sacrifice grammar for clarity. No info for a section →
`<!-- TODO: fill in -->`, don't hallucinate; a whole section that doesn't apply (no UI, no
Modulith, no message broker) → **delete it**, don't pad with TODOs. **Augment mode never clobbers
user content** — fill TODO slots or append a clearly marked subsection, leave the rest alone;
pre-existing docs are read-only, cross-link instead of editing.

What each doc must carry from the Java discovery: `docs/java.md` gets concrete module/package
names and a small reference graph (cross-link, don't restate, the other docs); `architecture.md`
names framework + persistence in the stack summary (e.g. "Spring Boot 4.1 + PostgreSQL + Spring
Data JDBC") and the Modulith boundary gate if in play; `data-model.md` records the migration tool
(Flyway/Liquibase), location, and workflow (startup vs CI); `infrastructure.md` records the CI
system, config/profile layering, and deployment shape (layered jar, Docker, or WAR on an app
server).

ADR seeds — 1–3 decisions **clearly made**, with Status, Context (alternatives), Decision,
Consequences (favor/against): build tool + JDK target; persistence framework and why; Spring
Modulith adoption if `@ApplicationModule` is present (no .NET equivalent); deployment target if
detected. Never fabricate the rationale.

---

## Phase 4 — Wire (AGENTS.md + CLAUDE.md)

Generate `AGENTS.md` strictly as a **table of contents**:

- **Opening:** 2 lines max (project name + one-line purpose).
- **"Where to find things":** every doc, one line each, including `docs/java.md` ("deep Java
  context: module graph, JDK target, DI, persistence, Modulith boundaries") and any pre-existing
  repo docs from Phase 1b.
- **"Commands":** the 3–6 commands a developer actually runs — wrapper over bare tool, `Makefile`
  over raw invocations, profile-activation flag if needed locally, unit/integration split
  (checklist §9).
- **"Non-obvious rules":** the user's Phase 2 answers with a short rationale, plus the mechanical
  gotchas the checklist surfaced (BOM-managed versions, Modulith boundaries, ArchUnit-enforced
  layering — checklist §3, §6, §10) — agents reliably get these wrong.
- **"Testing"** / **"Code style":** one paragraph each, naming the frameworks/gates from Phase 1.
- **"Security":** no secrets committed, `.env` not in VCS, don't log PII.

Enforce the ~80-line ceiling — move overflow into `docs/java.md`.

`CLAUDE.md` is one line: `@AGENTS.md`, with a comment explaining that it delegates.

---

## Phase 5 — Validate claims (Claimify-inspired)

Generated docs hallucinate. Before finishing, surface the load-bearing factual claims you wrote
and confirm the uncertain ones with the user. Adapted from Microsoft Research's **Claimify** —
atomic, self-contained, verifiable claims, **flag ambiguity instead of guessing**. Follow
`references/claim-validation.md` in full; the six steps in short:

1. **Select** load-bearing claims (build tool + version, JDK target, persistence framework, DI
   framework, commands, key entities, dependency versions, non-obvious rules) — skip TODOs.
2. **Atomize + tag** — one statement each, with source (`file:line`/`inferred`) and confidence
   `high`/`medium`/`low`.
3. **Flag ambiguity** — more than one plausible reading, or no clear source; never silently keep
   a low-confidence claim.
4. **Verify with the user** — a compact ledger; `AskUserQuestion` for top binary confirmations,
   plain chat for the rest.
5. **Apply** corrections; downgrade unconfirmed `low` claims to `<!-- TODO: verify -->`.
6. **Persist** the ledger to `docs/claims-ledger.md` (format in the reference).

---

## Phase 6 — Verify

1. Print a tree of files written (or augmented).
2. Check every link in `AGENTS.md` and `docs/java.md` resolves to a file that exists (use Read).
3. Remind the user: commit with
   `git add AGENTS.md CLAUDE.md docs/ && git commit -m "docs: bootstrap Java context pack for AI coding agents"`;
   fill `<!-- TODO -->` markers, review the ADRs, skim `docs/claims-ledger.md` for anything
   unverified; if quality gates were absent, consider Checkstyle/Spotless + an arch-linting test
   (ArchUnit, or `ApplicationModules.verify()` if modules exist); re-run
   `/sdlc-ia:agent-context-java` later — it augments, never overwrites.

---

## Reference

- `references/java-inspection.md` — the full Java discovery checklist (Phase 1c).
- `references/claim-validation.md` — the Claimify-inspired claim-validation procedure (Phase 5).
- `templates/es/` — the doc skeletons.

## Rules

- Do NOT write application code.
- Do NOT overwrite existing docs without explicit user opt-in; enrich by filling TODOs or
  appending clearly marked sections.
- Do NOT fabricate framework or dependency versions, providers, endpoint names, or schema you
  haven't read.
- DO leave `<!-- TODO -->` markers where human input is needed, and delete sections that don't
  apply rather than padding them.
- DO keep every doc focused: each has one job, delegated from AGENTS.md.
