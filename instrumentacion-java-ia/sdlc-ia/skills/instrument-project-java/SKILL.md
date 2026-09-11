---
name: instrument-project-java
description: Install the deterministic instrumentation layer in a Maven-based Java/Spring repository so an AI coding agent cannot ship work that breaks the team's rules — reproducible inputs (wrapper pin, BOM-managed versions), a strict `-Werror` build with Error Prone, verifiable style (Spotless + Checkstyle), a single Makefile entry point, pre-commit/pre-push gates (Lefthook), secret scanning (gitleaks), Spring-Modulith-aware architecture fitness functions (ArchUnit), a GitHub Actions CI workflow, dependency vulnerability scanning (OWASP Dependency-Check plus Dependabot), new-code test coverage (JaCoCo), bug-pattern analysis (SpotBugs) and unit/integration suite separation (Failsafe). Every gate is proven to fail before the run ends. Invoke with `/sdlc-ia:instrument-project-java`.
disable-model-invocation: true
---

# instrument-project-java — Turn the Repo Into Its Own Reviewer

You are installing the **deterministic instrumentation** layer: everything a machine can verify on
its own, in milliseconds, with no ambiguity — a sensor the agent hits by itself, **before any human
reads the diff**.

You install twelve controls, prove each one fails when it should, and record them in `AGENTS.md`.

| # | Control | Artifact | What it prevents |
|---|---------|----------|-------------------|
| 1 | Reproducible inputs | `.mvn/wrapper/*`, `pom.xml` BOMs | Two machines resolving a different Maven or dependency tree |
| 2 | Strict build | `maven-compiler-plugin` `-Werror` + Error Prone | A warning reaching `main`; a type-level bug compiling clean |
| 3 | Style | `.editorconfig`, Spotless, Checkstyle | Formatting noise and naming drift in every diff |
| 4 | Entry point | `Makefile` (patched) | Nobody knowing how the repo is verified |
| 5 | Shift-left | `lefthook.yml` | Errors surfacing at review time |
| 6 | Secrets | `gitleaks` | A credential reaching the history |
| 7 | Architecture tests | `archunit-junit5` / Spring Modulith | The dependency rule silently breaking |
| 8 | CI | `.github/workflows/ci.yml` | Local gates being skipped |
| 9 | Dependency vulnerabilities (SCA) | `dependency-check-maven`, `dependency-check-suppressions.xml`, `.github/dependabot.yml` | A dependency with a known CVE shipping unnoticed |
| 10 | Test coverage | `jacoco-maven-plugin`, new-code rule | New code arriving with no test, unnoticed |
| 11 | Bug patterns | `spotbugs-maven-plugin` | Defects that compile and pass style |
| 12 | Test suite separation | `maven-failsafe-plugin`, `make test` / `make verify` | Fast and slow tests running as one, so neither can be required |

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
  another language — match the file you edit, write new artifacts in English, and say so if that
  leaves a file bilingual.
- **Never commit.** The only `git` writes are the break-and-restore of Phase 4, undone before it
  ends.

---

## Phase 1 — Discover (silent)

Use Glob, Grep, Read, and read-only Bash. Work through `references/inspection.md` in full: Maven
(or Gradle), module graph, Java target, BOM-managed vs. inline dependency versions, test setup,
which of the nine controls already exist and in what state, existing GitHub Actions workflows, and
context docs. Then
classify the architecture shape with `references/architecture-discovery.md`.

Report the state as a table (control, `present`/`partial`/`missing`, what you found), plus JDK/Maven
versions and the detected shape. **A partial control is more dangerous than a missing one** — the
team believes it's covered.

---

## Phase 2 — Prerequisites

Check tooling per OS; install nothing yourself.

| Tool | Check | macOS | Windows | Linux |
|---|---|---|---|---|
| JDK / Maven | `./mvnw -v` | — | — | — |
| Lefthook | `lefthook version` | `brew install lefthook` | `winget install evilmartians.lefthook` | `go install github.com/evilmartians/lefthook@latest` |
| `make` | `make --version` | ships with Xcode CLT | `winget install ezwinports.make` | ships with the distro |
| gitleaks (opt-in) | `gitleaks version` | `brew install gitleaks` | `winget install gitleaks` | `apt install gitleaks` on Debian trixie+/Ubuntu 25.04+; older LTS needs the release binary |
| NVD API key (opt-in, control 9) | `NVD_API_KEY` set in the environment | request one at `https://nvd.nist.gov/developers/request-an-api-key` — free, no install; without it the scan still runs, throttled (first run 20+ min) | same | same |

`make` does not ship with Windows. If missing, surface the `winget` command as a prerequisite; do
not silently switch task runners.

If a tool was installed via `winget` this session, open a new terminal before re-checking — user
`PATH` doesn't refresh in an already-open one.

---

## Phase 3 — Agree on scope, then apply

**Before writing anything, confirm the working tree is clean** (`git status`, excluding the agent's
own untracked tooling). If not, stop and tell the user.

Ask only what Phase 1 could not answer, in plain language (spell out acronyms, state costs):

1. **Which controls to install** — default all twelve; `present` controls are reported, not
   reinstalled; `partial` ones get both exits (complete it, or remove the dead config).
2. **Style formatter** — `spotless-maven-plugin` needs one. `google-java-format` is the zero-config
   default (2-space); `palantir-java-format` suits teams wanting 4-space. Pick one, say why in the
   report.
3. **Brownfield reformat** — an existing tree means the first `spotless:apply` reformats everything
   in one commit. Offer `Reformat now` or `ratchetFrom` scoped to a base branch (only files changed
   since `origin/<default>`, needs CI `fetchDepth: 0`, already in the templates). Never reformat
   silently.
4. **CI** — only if Phase 1 found no workflow under `.github/workflows/`: `GitHub Actions` /
   `Skip for now`. This skill writes **GitHub Actions only** — the scope the plugin `README.md`
   declares. A repository whose CI lives elsewhere (Jenkins, GitLab, Azure Pipelines) gets control
   8 reported as out of scope, with the `make ci` target it can call from there — never a
   hand-adapted pipeline for a platform this skill has no template for.
5. **Secret scanning** — off by default (curation cost, one `.gitleaksignore` entry per false
   positive). Offer `Yes — pre-commit and CI`, `CI only`, `Skip`.
6. **`commit-msg` (Conventional Commits)** — **not proposed by default.** Install only when
   `git log --oneline -20` already follows the convention. Descriptive, unprefixed subjects (this
   monorepo's log) get no hook — imposing a convention nobody uses is a team decision, not an
   instrumentation fix, and the report says so.
7. **Dependency vulnerability scan (SCA)** — off by default, like secrets, because it has a running
   cost: an NVD API key (free) or a throttled first run of 20+ minutes, a suppression file to
   curate (one entry per accepted finding, with its reason), and a pre-existing tree that may
   already carry a High CVE and block `make ci` from day one — say so. Offer
   `Yes — make ci and Dependabot`, `Report only (never fails the build)`, `Skip`.
8. **Coverage threshold (control 10)** — on by default, but the rule is scoped to **new code only**
   (`limit` on `CLASS`/`LINE` over the changed set), never a repo-wide number. A repo-wide
   threshold on a brownfield is born red and its only exit is lowering it until it means nothing;
   a new-code rule is born green and applies where it matters — the same "encode what the repo
   already does" principle as control 7. It fails **CI only**, never `make check`: coverage is slow,
   and a slow local gate gets bypassed with `--no-verify`. Confirm the percentage with the user;
   do not invent one.
9. **Bug patterns (control 11)** — off by default, like secrets and SCA, and for the same reason:
   Checkstyle in strict mode prevents *new* debt and starts green, while SpotBugs over a brownfield
   starts red, and a control born red is switched off in its first week. Install **SpotBugs only —
   never PMD alongside it**: two new analyzers shouting at once is the fastest way to get both
   muted, and PMD overlaps Checkstyle across much of its ruleset. When enabled it **fails
   `make check`** — a sensor that only reports is a sensor nobody reads. Offer
   `Yes — fails make check`, `Report only`, `Skip`.
10. **Test suite separation (control 12)** — on by default. Split unit from integration via
    `maven-failsafe-plugin` (`*IT` / `*ITCase`) so `make test` stays fast and `make verify` runs the
    slow set. Install **the split and the profile only** — never Testcontainers, RestAssured or any
    test framework the repo has not chosen: picking a testing stack for the team is more invasive
    than anything else this skill does, and contradicts its own rule of encoding what the repo
    already does. Growing the tests themselves is `/sdlc-ia:legacy-test-harness`, a different skill.

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
| 2 | Strict build | Add an unused import; then a self-comparison (`x == x`) | `mvn compile` fails twice — once on the warning, once on the Error Prone check by name |
| 3 | Style | Reorder imports in a real file | `make lint` fails, naming the file |
| 4 | Entry point | No break needed | `make help` lists every target; `make check` chains them |
| 5 | Shift-left | Stage a bad file, commit with a disposable identity | `BLOCKED` — confirmed by `HEAD` before/after, not the printed text |
| 6 | Secrets | Stage `AKIA4SFODNN7QWERTZXC` — never `AKIAIOSFODNN7EXAMPLE` | Blocked, naming the rule |
| 7 | Architecture | Add a forbidden dependency + real usage | `mvn test` fails, naming rule and type |
| 8 | CI | Cannot be broken locally | Verify by inspection — pinned JDK, calls `make ci`, every branch |
| 9 | Dependency vulnerabilities | Add a compile-scope dependency with a known High CVE | `make sca` fails, naming the CVE and the artifact; `dependabot.yml` verified by inspection |
| 10 | Test coverage | Add a new class with a branch and no test | The coverage check fails, naming the class and the missed percentage — and the same class **with** a test passes |
| 11 | Bug patterns | Introduce a known pattern (e.g. a boxed comparison by `==`) | `make check` fails, naming the SpotBugs rule |
| 12 | Suite separation | Add a failing `*IT` alongside a passing unit test | `make test` stays **green** and `make verify` fails — if both go red, the split did not take |

Control 10's break has **two halves**: red without a test proves the rule fires, green with one
proves it is scoped to new code and not to the whole repo. A rule that stays red either way is a
repo-wide threshold in disguise, which scope question 8 ruled out.

Controls 5–6 are verified by a **real commit**; if the hook does not fire, undo it with
`git reset --soft HEAD~1`. Restore every change, run `make check`, capture the real output. **Do
not report success with a gate in the red.**

---

## Phase 5 — Document and report

Update `AGENTS.md`/`CLAUDE.md` if present — a "Checks to run" section, `make hooks` in setup, the
layering rules now enforced, the CI paragraph. **Update what exists; do not create the doc pack** —
if missing, report the gap and point at `/sdlc-ia:agent-context-java`.

For control 8 on GitHub, say explicitly that the workflow is **written but not yet enforced**:
nothing blocks a merge until a Ruleset on the integration branch requires that workflow as a status
check and at least one approval. That Ruleset, a `CODEOWNERS` file and any deployment Environment
are GitHub repository settings this skill never writes — report them as the remaining manual step
for whoever administers the repo.

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
- Do NOT write a repo-wide coverage threshold. Control 10 is scoped to new code, or it is not
  installed.
- Do NOT install PMD next to SpotBugs, and do NOT turn control 11 on by default.
- Do NOT add a test framework (Testcontainers, RestAssured, WireMock) under control 12 — it
  separates the suites the repo already has and nothing more.
- Do NOT commit or push.
- Do NOT touch GitHub repository settings (Rulesets, Environments, `CODEOWNERS`) — name them as
  the manual step that remains.
- DO leave every exception (`allowEmptyShould`, suppression) commented with a reason.
- DO tell the user what you skipped and why.
