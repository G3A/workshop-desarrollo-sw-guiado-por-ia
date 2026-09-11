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

- **`instrument-agent-java` now narrows what MCP widened** — permission rules over `mcp__*` plus a
  ninth hook. The skill's own ordering rule, *"MCP first, hooks second, because MCP only adds
  capability and hooks take it away"*, was being executed halfway: it registered the servers that
  widen the agent's reach and then declined to configure the one deterministic mechanism that
  narrows them.
  - **A hard rule was lifted, deliberately and narrowly.** The skill used to declare it *never*
    touches `permissions`. That was incoherent with itself: `references/hook-catalog.md` already
    tells the user to close a gap with "a `Read` deny rule in permissions, not a hook" while the
    skill refused to write one. It now writes that key **only after an explicit scope answer** and
    **only `mcp__*` matchers**; every other entry stays the user's.
  - **Two mechanisms, split by what they can decide on.** Permission rules decide on the **tool
    name** — the general posture, readable at a glance and auditable in a diff. **Hook 9**
    (`mcp-write-guard.sh`) decides on the **arguments**: which repository, which SQL statement —
    what a name cannot express. The same division the skill already makes between Git hooks and
    agent hooks.
  - **The posture is deny writes, allow reads**, sorted by consequence rather than by server.
    Two alternatives are rejected in writing: `ask` for everything is safe and exhausting, and gets
    switched off within days; and a server's own `readOnlyHint` is not usable, because the
    specification says to treat annotations as untrusted — the server declaring them is the one you
    would be watching.
  - **Verification needs both halves.** A deny that fires, *and* a read that still works: a posture
    that also blocks reads turns the servers just registered into dead weight, and that is found
    mid-task a week later. Hook 9 is then triggered with a call the rules allow but whose arguments
    should not pass — a hook that never fires because the rules caught everything is one to delete.
  - **It does not break `github-plan-build`, and the report says so.** That skill goes through the
    `gh` CLI over Bash, not the GitHub MCP server, so none of the deny matchers apply to it.
    Without that sentence, the first reader of the deny list switches the control off to unblock
    something that was never blocked.
  - The regression suite grows from 146 to **225 cases**, all green. The new ones cover what an
    allow-list keyed on tool names would let through: a write aimed at another repository, and a
    `DELETE` through a read-shaped query tool — including the anchoring that keeps
    `SELECT updated_at …` and a literal containing `DELETE` passing.

- **`instrument-github-repo`, a new skill** — the eighth — closing the box that left every other
  control decorative. `instrument-project-java` installs twelve controls and writes the CI
  workflow, and **a workflow that runs blocks nothing**: what stops a merge is the Ruleset that
  requires it. Until now that step was manual, so an instrumented repository had every sensor and
  no judge.
  - **Its own skill, not a phase of the other one**, because it changes repository settings through
    the GitHub API rather than files in the working tree: different permissions, different failure
    modes, and no `git checkout` to undo them.
  - **Never overwrites an existing ruleset.** It fetches it, compares field by field, and reports a
    three-column table — what it has, what the skill would write, what that would change — then
    stops. Updating is a person's call. It never deletes one, for any reason.
  - **Proves the gate by blocking**, not by trusting a `201`. It opens a throwaway PR against the
    protected branch and confirms GitHub reports `BLOCKED` for the reason that was configured. Three
    ways a ruleset is accepted and still protects nothing are documented, and all three return
    `201`: a bare branch name instead of a full ref, `enforcement: evaluate`, and a required context
    no run ever produces.
  - **The asymmetry between the two branches is encoded, not copied.** The release ruleset uses
    `strict_required_status_checks_policy: false` on purpose: because integration merges by squash,
    the integration branch never carries previous releases' merge commits, so with `true` every
    release would be blocked forever as out of date.
  - **Every bypass is explained.** The one honest reason to open one — a single-person repository
    cannot satisfy a required approval, since nobody approves their own PR — is stated out loud,
    with its removal condition recorded in `AGENTS.md`.
  - The required status check's `context` is read from a real completed run, never guessed from the
    workflow file name; the skill refuses to proceed without one, because a context that never
    appears blocks every merge forever and looks like a working gate for the first hour.
  - Every rule shape in `references/ruleset-anatomy.md` was read from live rulesets via the API,
    not from documentation.

- **`agent-context-java` now writes `REVIEW.md` and a PR template**, closing the last two boxes of
  the method's fourth verification layer — the one no sensor replaces.
  - **A third file, separate on purpose.** `AGENTS.md` holds the rules the agent respects *while
    generating*; `REVIEW.md` holds what to look at in a diff that *already exists*. They load in
    different places: the cloud PR-review service reads `REVIEW.md`, the local `/code-review` reads
    the guide file. A criterion that must hold in both belongs in `AGENTS.md`.
  - **Six categories, fifteen items.** The six categories are the method's (verification layer 4:
    API hallucinations, project fidelity, tests of the spec, technical debt, trade-offs, running
    the change yourself). The **fifteen concrete items are this template's own wording, not a
    quotation** of any source — the skill says so when it reports, and invites the team to change
    them.
  - **The PR template links instead of repeating.** Six boxes, one per category, pointing at
    `REVIEW.md`. A second copy of the list drifts from the first within a few sprints.
  - **Never a CI check**, deliberately: a workflow that requires the boxes ticked turns judgement
    into paperwork — all six get ticked unread and the record starts lying. They leave a trace of
    what was reviewed; they do not guarantee it.
  - Both files honour augment mode: an existing `REVIEW.md` or PR template is filled and appended
    to, never replaced. Phase 6 now also checks the template's relative link to `REVIEW.md`
    resolves — a broken one only shows up months later, mid-review.
  - The template closes with the promotion rule in both directions: something said by hand for the
    third time is a criterion missing from the file, and a criterion a machine could check stops
    being text and becomes a sensor.

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
