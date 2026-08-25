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

What each doc must carry from the Java discovery, and the ADR seeds, are in
`references/doc-content-map.md`.

---

## Phase 4 — Wire (AGENTS.md + CLAUDE.md)

Generate `AGENTS.md` strictly as a **table of contents** — the section list and what goes in each
is in `references/doc-content-map.md`. Enforce the ~80-line ceiling — move overflow into
`docs/java.md`.

`CLAUDE.md` is one line: `@AGENTS.md`, with a comment explaining that it delegates.

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

- `references/java-inspection.md` + `references/java-inspection-2.md` — the full Java discovery
  checklist (Phase 1c).
- `references/doc-content-map.md` — what each doc carries, and the `AGENTS.md` section list
  (Phase 3, Phase 4).
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
