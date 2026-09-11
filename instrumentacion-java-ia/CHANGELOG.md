# Changelog

All notable changes to the `sdlc-ia` plugin. Versions follow the `version` field in
`sdlc-ia/.claude-plugin/plugin.json`. `dev` is the integration branch; **`main` is what has been
released**. Dates are the date a release PR (`dev` → `main`) landed on `main`.

**One version per release.** A PR to `dev` that changes a skill files its entry under the
"unreleased" heading; the release PR `dev` → `main` bumps the `version` field, turns that heading
into the new version and dates it. The version is what lets a machine say which copy it runs
(`claude plugin list`) — it is not what refreshes the install: Claude Code copies the plugin into
a cache and `claude plugin install` never refreshes an already-installed plugin, even after a
version bump (verified 0.1.0 → 0.2.0). Updating on any machine is `update.ps1` / `update.sh` at
the plugin root, which uninstalls and reinstalls (see the README, "Actualizar cuando sale una
versión nueva", and `AGENTS.md`).

## [unreleased]

### Added

- **`instrument-project-java` grows from nine controls to twelve**, closing four boxes the
  `playbook-sdlc-ia` diagram had in red or amber. Every parameter below was decided in the coverage
  questionnaire, not chosen here.
  - **Error Prone, inside control 2** (not a control of its own — it is one more compiler
    argument). Starts at the **ERROR set only**, leaving warnings visible: that set is tuned for no
    false positives, so it is green on almost any repository, while promoting everything breaks a
    brownfield on day one and gets the tool uninstalled instead of the bar raised.
  - **Control 10 · test coverage** (JaCoCo). The rule is scoped to **new code, never the whole
    repository** — a repo-wide ratio on a brownfield is born red and its only exit is lowering it
    until it means nothing, the same failure control 7 avoids by encoding what the repo already
    does. It fails **CI only**: coverage is slow, and a slow local gate is bypassed with
    `--no-verify` within a week.
  - **Control 11 · bug patterns** (SpotBugs). **Opt-in**, like secrets and SCA and for the same
    reason: Checkstyle prevents new debt and starts green, SpotBugs over a brownfield starts red.
    **SpotBugs alone — never PMD alongside it**, since two new analyzers shouting at once is the
    fastest way to get both muted. When enabled it fails `make check`. This is also what makes
    FindSecBugs possible later.
  - **Control 12 · test suite separation** (Failsafe). Installs **the split and the profile only** —
    never Testcontainers, RestAssured or any framework the repo has not chosen, because picking a
    testing stack for the team is more invasive than anything else this skill does. `make check`
    runs `test`, `make ci` runs `verify`; that one difference is the control.
  - Each one carries its **break-and-restore procedure**. Control 10's has two halves on purpose:
    red without a test proves the rule fires, green with one proves it is scoped to new code — a
    rule red in both is a repo-wide threshold in disguise. Control 12's break must show `make test`
    green while `make verify` is red; both red means the split never took.

### Changed

- **`instrument-agent-java` no longer calls itself "the non-deterministic instrumentation layer".**
  It installs one half of each: MCP servers are non-deterministic (the model decides when to call
  a tool and with which arguments), while the eight `type: command` hooks are deterministic (the
  agent's lifecycle fires them at a fixed point and a shell script, not the model, decides allow /
  block / report). The old label contradicted the axis the `proceso-operacional-con-ia` viewer
  states — deterministic only when both the trigger and the decision stay outside the model — and
  the viewer already tags all eight hooks `determinista` and the three MCP servers
  `noDeterminista`. Wording only; no change to what the skill installs.

### Fixed

- **`README.md` said the plugin has four skills** while the table below it listed seven, and typed
  "una catálogo de 8 hooks".
- **`docs/skills/instrument-agent-java-es.md` listed seven hook scripts of eight** under "Qué
  archivos toca o crea", omitting `block-dangerous-powershell.sh` — hook 8 of its own table, and a
  file that ships in `templates/hooks/`.

## [0.2.0] — 2026-09-09 (first release to `main`)

### Added

- **`update.ps1` / `update.sh`** — one command to bring any machine to the plugin version in its
  clone: `git pull --ff-only`, register the `sdlc-ia` marketplace on that folder (or re-point it),
  `marketplace update` + `install`, uninstall-and-reinstall when the version did not change, and
  a file-by-file check that the installed cache equals the source.
- **`AGENTS.md`** for the plugin, with the version-bump rule above as a hard rule, and an
  `AGENTS.md` at the monorepo root with the conventions the skills already look for there
  (integration branch `dev`, releases to `main` by PR, `feat/`/`fix/`/`docs/` prefixes, the
  `Asistido-por-IA` trailer, viewer/skill sync, PowerShell-compatible commands).

- **`agent-context-java`** — generates a documentation pack for a Java/Spring repository
  (`AGENTS.md`, `CLAUDE.md`, `docs/business.md`, `docs/architecture.md`, `docs/data-model.md`,
  `docs/infrastructure.md`, `docs/adrs/`, plus a `docs/java.md` deep-dive) so an AI coding agent
  can reason about it without guessing. Follows a Claimify-inspired claim-validation procedure —
  every load-bearing factual claim is atomized, tagged with its source and confidence, and
  verified with the user before being persisted to `docs/claims-ledger.md`.

- **`instrument-project-java`** — installs nine deterministic controls in a Java/Maven
  repository: reproducible inputs (Maven wrapper pin, BOM-managed dependency versions), a strict
  build (`-Xlint:all -Werror`), verifiable style (Spotless + Checkstyle), a single `make check`
  entry point, Lefthook pre-commit/pre-push gates, gitleaks secret scanning, ArchUnit
  architecture fitness functions, a GitHub Actions CI workflow (the only CI platform this plugin
  writes, matching its declared GitHub-only scope), and — opt-in,
  like secrets — dependency vulnerability scanning: OWASP Dependency-Check behind `make sca`
  (fails `make ci` on CVSS ≥ 7, NVD key from the environment, suppressions with a reason) plus
  Dependabot version updates on GitHub. Every gate is proven to fail before the run reports
  success. When the target repository already has some
  of these controls wired up — `base-conocimiento`, the worked example behind this skill, already
  has ArchUnit + Spring Modulith verification running in `mvn test` — the skill verifies and
  reports findings instead of reinstalling from scratch.

- **`instrument-agent-java`** — registers the team's MCP servers in `.mcp.json` and installs a
  catalogue of eight Claude Code hooks in `.claude/settings.json`, backed by portable, dependency-
  free bash scripts (bash 3.2, no `jq`, `set -u` without `pipefail`): a secret read-guard, scoped
  Spotless auto-formatting, a dangerous-command blocker, a session-start dependency sweep, an
  audit log, and guards for centrally-managed dependency versions and Flyway/Liquibase migration
  files. Two hooks are on by default; the rest are offered, and hidden when the repository doesn't
  have the artifact they protect. Ships with its own regression suite (`tests/`, 146 cases).

- **`github-plan-build`** — the ticket → plan → build → verified-PR loop, targeting GitHub
  Issues. Shares its Steps A–J build-loop procedure with the tracker-agnostic pattern already
  established upstream (`linear-plan-build`/`ado-plan-build`); resolves GitHub's own auto-close
  syntax (`Closes #<n>`) as the issue-linking token, and detects whether the repository tracks
  in-progress/in-review status via labels or GitHub Projects v2 before choosing which one to
  write to. Every commit it makes, and the PR body, end with the git trailer
  `Asistido-por-IA: <model id>` naming the model that ran the session, so a repository can
  separate AI-assisted commits from the rest (`git log --format='%(trailers:key=Asistido-por-IA)'`)
  and survive a squash merge that takes the PR body as the commit message. An optional
  `confirm-push` argument adds a second checkpoint between the commit and the push, so a team
  that wants the developer to own the push (the way the process viewer models it) can keep the
  rest of the loop autonomous. The branch is cut from, and the PR opened against, a resolved
  `BASE-BRANCH` — the integration branch the repo documents, falling back to the remote's
  default — instead of always the default branch, which on a repo that integrates on `dev` and
  releases to `main` produced PRs against the wrong branch. Once the plan is approved it
  is posted on the issue as a comment (steps, decisions, assumptions, out of scope), and Step F
  commits one green step at a time with `Refs #<n>`, the last one carrying `Closes #<n>`, so the
  history reads task by task. A missing milestone is reported as "in no sprint", and a repository
  with neither status labels nor a Projects v2 board is pointed at the viewer's node that creates
  one.

- **`requirement-to-spec-java`** — turns a business requirement document (Word via `pandoc`,
  PDF, Excel, Markdown, plain text) into a specification and a task breakdown *before* an issue
  exists: silent discovery of the Java/Spring surface the requirement touches (REST controllers,
  JPA entities, migrations, documentation that would go stale), a scope round of at most four
  questions per call across six always-active categories, and a destination that is always asked —
  a GitHub parent issue with native sub-issues (and, on request, the milestone that represents
  the sprint, applied to parent and children), or `docs/specs/<slug>/spec.md` + `tasks.md`. Never
  writes code, never opens a PR, never touches git. Adapted from arkandia's stack-agnostic
  `requirement-to-spec` (0.5.0).

- **`debt-triage`** — triages the findings an analyzer already reports (SonarQube, CodeQL,
  ESLint, Checkstyle, PMD, SpotBugs, …) instead of installing a new one: groups open findings by
  rule, gives every one a verdict — real, false positive, accepted risk — with the reason next to
  the code, writes the fix for the real ones in the working tree, and never applies a tool's
  auto-fix without reading the call site. Never commits.

- **`legacy-test-harness`** — conditions a legacy repository of any stack and grows real tests
  across five layers (unit/collaboration, contract, acceptance, performance, security) on code
  that already ships: fingerprints the stack, maps the seams Feathers-style, asks which layers
  to generate this run, and never edits production code — a seam that needs a production edit is
  proposed as a candidate issue (`Costura: <what>`, label `deuda-tecnica`) for the user to file.

- **Shell-neutral commands across the plugin** — every command a skill runs works unchanged in
  Windows PowerShell 5.1, PowerShell 7 and bash (the process viewer's copy-paste blocks, by
  contrast, target PowerShell 5.1/7 and say so): `gh --jq` instead of `sed`/`tr`/`cut` pipelines
  (and no quotes inside a `--jq` expression — PowerShell 5.1 strips them), two commands instead of `&&`, the
  agent's Read/Glob/Grep tools instead of `ls`/`find`/`grep`, and PowerShell + bash side by side
  where no neutral form exists. Skills that declare `allowed-tools` pre-approve the same commands
  under `PowerShell(...)` as under `Bash(...)`.
