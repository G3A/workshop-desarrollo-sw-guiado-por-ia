# What each doc carries

> Called from **Phase 3** and **Phase 4** of `SKILL.md`.

## From Phase 3 — Java discovery into the docs

`docs/java.md` gets concrete module/package names and a small reference graph (cross-link, don't
restate, the other docs); `architecture.md` names framework + persistence in the stack summary
(e.g. "Spring Boot 4.1 + PostgreSQL + Spring Data JDBC") and the Modulith boundary gate if in
play; `data-model.md` records the migration tool (Flyway/Liquibase), location, and workflow
(startup vs CI); `infrastructure.md` records the CI system, config/profile layering, and
deployment shape (layered jar, Docker, or WAR on an app server).

## ADR seeds

1–3 decisions **clearly made**, with Status, Context (alternatives), Decision, Consequences
(favor/against): build tool + JDK target; persistence framework and why; Spring Modulith adoption
if `@ApplicationModule` is present (no .NET equivalent); deployment target if detected. Never
fabricate the rationale.

## `AGENTS.md` sections (Phase 4)

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
