---
name: instrument-project-java
description: Install the deterministic instrumentation layer in a Maven-based Java/Spring repository so an AI coding agent cannot ship work that breaks the team's rules — reproducible inputs (wrapper pin, BOM-managed versions), a strict `-Werror` build, verifiable style (Spotless + Checkstyle), a single Makefile entry point, pre-commit/pre-push gates (Lefthook), secret scanning (gitleaks), Spring-Modulith-aware architecture fitness functions (ArchUnit), and a CI pipeline (GitHub Actions or Azure Pipelines). Every gate is proven to fail before the run ends. Invoke with `/sdlc-ia:instrument-project-java`.
disable-model-invocation: true
---

# instrument-project-java — Turn the Repo Into Its Own Reviewer

You are installing the **deterministic instrumentation** layer: everything a machine can verify on
its own, in milliseconds, with no ambiguity — a sensor the agent hits by itself, **before any human
reads the diff**.

You install eight controls, prove each one fails when it should, and record them in `AGENTS.md`.

| # | Control | Artifact | What it prevents |
|---|---------|----------|-------------------|
| 1 | Reproducible inputs | `.mvn/wrapper/*`, `pom.xml` BOMs | Two machines resolving a different Maven or dependency tree |
| 2 | Strict build | `maven-compiler-plugin` `-Werror` | A warning reaching `main` |
| 3 | Style | `.editorconfig`, Spotless, Checkstyle | Formatting noise and naming drift in every diff |
| 4 | Entry point | `Makefile` (patched) | Nobody knowing how the repo is verified |
| 5 | Shift-left | `lefthook.yml` | Errors surfacing at review time |
| 6 | Secrets | `gitleaks` | A credential reaching the history |
| 7 | Architecture tests | `archunit-junit5` / Spring Modulith | The dependency rule silently breaking |
| 8 | CI | Workflow / pipeline | Local gates being skipped |

## Philosophy

- **Never hardcode a version.** Read JDK/Maven from the repo's own wrapper and POM; resolve
  gitleaks and marketplace actions at install time, then pin.
- **Encode what the repo already does, not what it should do.** Every arch-test rule must pass the
  moment you write it — red on install is a refactoring proposal, not a sensor.
- **Discover before you write.** Single/multi-module, Spring Boot or plain Jakarta EE, Modulith or
  not — assume nothing.
- **Merge, never clobber.** A `Makefile` that already exists gets a patch, not a replacement.
- **An exception without a comment is invisible debt.** Every `allowEmptyShould`, suppression, or
  skipped rule carries a reason.
- **One definition of "the code is fine."** CI calls the Makefile; it does not restate its steps.
- **A gate nobody saw fail is not a gate.** Verify by breaking each control —
  `references/verification.md`.
- **Fail fast, with a clear message.** A slow pre-commit gets uninstalled within a week.
- **Everything you write is in English** — config, comments, target descriptions, `fail_text`, CI
  step names — regardless of the conversation language. Exception: prose that already exists in
  another language (a Spanish `AGENTS.md` or `Makefile` comment) — match the file you edit, write
  new artifacts in English, and say so if that leaves a file bilingual.
- **Never commit.** The only `git` writes are the break-and-restore of Phase 4, undone before it
  ends.

---

## Phase 1 — Discover (silent)

Use Glob, Grep, Read, and read-only Bash. Work through `references/inspection.md` in full: Maven
(or Gradle), module graph, Java target, BOM-managed vs. inline dependency versions, test setup,
which of the eight controls already exist and in what state, CI platform, context docs, and
`git log --oneline -20` for commit-convention evidence. Then classify the architecture shape with
`references/architecture-discovery.md`.

Report the state as a table (control, `present`/`partial`/`missing`, what you found), plus JDK/Maven
versions and the detected shape. **A partial control is more dangerous than a missing one** — the
team believes it is covered.

---

## Phase 2 — Prerequisites

Check tooling per OS; install nothing yourself.

| Tool | Check | macOS | Windows | Linux |
|---|---|---|---|---|
| JDK / Maven | `./mvnw -v` | — | — | — |
| Lefthook | `lefthook version` | `brew install lefthook` | `winget install evilmartians.lefthook` | `go install github.com/evilmartians/lefthook@latest` |
| `make` | `make --version` | ships with Xcode CLT | `winget install ezwinports.make` | ships with the distro |
| gitleaks (opt-in) | `gitleaks version` | `brew install gitleaks` | `winget install gitleaks` | `apt install gitleaks` on Debian trixie+/Ubuntu 25.04+; older LTS needs the release binary |

`make` does not ship with Windows. If missing, surface the `winget` command as a documented
prerequisite; do not silently switch task runners.

---

## Phase 3 — Agree on scope, then apply

**Before writing anything, confirm the working tree is clean** (`git status`, excluding the agent's
own untracked tooling). If not, stop and tell the user.

Ask only what Phase 1 could not answer, in plain language (spell out acronyms, state costs):

1. **Which controls to install** — default all eight; `present` controls are reported, not
   reinstalled; `partial` ones get both exits (complete it, or remove the dead config).
2. **Style formatter** — `spotless-maven-plugin` needs one. `google-java-format` is the zero-config
   default (2-space); `palantir-java-format` suits teams wanting 4-space, closer to IDE defaults.
   Pick one, say why in the report.
3. **Brownfield reformat** — an existing tree means the first `spotless:apply` reformats everything
   in one commit. Offer `Reformat now` or `ratchetFrom` scoped to a base branch (only files changed
   since `origin/<default>`, needs CI `fetchDepth: 0`, already in the templates). Never reformat
   silently.
4. **CI platform** — only if Phase 1 found none or both: `GitHub Actions` / `Azure Pipelines` /
   `Skip for now`.
5. **Secret scanning** — off by default (curation cost, one `.gitleaksignore` entry per false
   positive). Offer `Yes — pre-commit and CI`, `CI only`, `Skip`.
6. **`commit-msg` (Conventional Commits)** — **not proposed by default.** Install only when
   `git log --oneline -20` already follows the convention. Descriptive, unprefixed subjects (this
   monorepo's own log) get no hook — imposing a convention nobody uses is a team decision, not an
   instrumentation fix, and the report says so.

Then install **in the order given in `references/apply.md`** — each control builds on the previous
one, and that file carries the per-control detail (artifact, key snippet, template pointer, the two
`allowEmptyShould` findings for control 7, the gitleaks pinning mechanics for control 8). Read every
template in `templates/` before writing: each opens with a header of placeholders and decisions;
follow it, delete the header before writing the target file. Run each control's command once and
confirm it passes before moving on — a broken control compounds.

---

## Phase 4 — Verify by breaking

Mandatory — follow `references/verification.md` for the full procedure and per-control command.
Summary:

| # | Control | Break | Expect |
|---|---|---|---|
| 1 | Reproducible inputs | Point `distributionUrl` at a non-existent Maven patch | `./mvnw -v` fails, naming the URL |
| 2 | Strict build | Add an unused import | `mvn compile` fails with an ERROR, not a warning |
| 3 | Style | Reorder imports in a real file | `make lint` fails, naming the file |
| 4 | Entry point | No break needed | `make help` lists every target; `make check` chains them |
| 5 | Shift-left | Stage a bad file, commit with a disposable identity | `BLOCKED` — confirmed by `HEAD` before/after, not the printed text |
| 6 | Secrets | Stage `AKIA4SFODNN7QWERTZXC` — never `AKIAIOSFODNN7EXAMPLE` | Blocked, naming the rule |
| 7 | Architecture | Add a forbidden dependency + real usage | `mvn test` fails, naming rule and type |
| 8 | CI | Cannot be broken locally | Verify by inspection — pinned JDK, calls `make ci`, every branch |

Controls 5–6 are verified by a **real commit**; if the hook does not fire, undo it with
`git reset --soft HEAD~1`. Restore every change, run `make check`, capture the real output. **Do
not report success with a gate in the red.**

---

## Phase 5 — Document and report

Update `AGENTS.md`/`CLAUDE.md` if present — a "Checks to run" section, `make hooks` in setup, the
layering rules now enforced, the CI paragraph. **Update what exists; do not create the doc pack** —
if missing, report the gap and point at `/sdlc-ia:agent-context-java`.

Report: tree of files created/modified; resolved versions (JDK, Maven, gitleaks, action majors); the
real `make check` output, green; every suppressed warning or `allowEmptyShould` and its reason;
architecture rules **not** written and why; migrations declined in Phase 3 (reformat, secrets, CI)
restated with the evidence this run produced. Do not commit — leave the diff for review.

---

## Reference

- `references/inspection.md` — the full discovery checklist (Phase 1).
- `references/apply.md` — per-control install detail, in order (Phase 3).
- `references/architecture-discovery.md` — classify the shape, derive rules that pass (Phase 1, 3).
- `references/arch-tests.md` — ArchUnit mechanics, both cases, `base-conocimiento` worked example.
- `references/verification.md` — the break-and-restore procedure, per control (Phase 4).
- `templates/` — the file skeletons and the Makefile patch.

## Rules

- Do NOT hardcode JDK, Maven, or dependency versions. Resolve them from the repo.
- Do NOT overwrite an existing `Makefile`, `pom.xml`, or config file without reading and merging it.
- Do NOT write architecture rules the repository does not follow, and do NOT assume a layered
  shape — derive it from the module graph.
- Do NOT report success until `make check` is green and every gate has been proven to fail.
- Do NOT install a `commit-msg` Conventional Commits hook without evidence from `git log`.
- Do NOT commit or push.
- DO leave every exception (`allowEmptyShould`, suppression) commented with a reason.
- DO tell the user what you skipped and why.
