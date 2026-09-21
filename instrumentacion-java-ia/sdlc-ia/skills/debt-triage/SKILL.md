---
name: debt-triage
description: Triage findings already reported by whatever static analyzer the repo runs today (SonarQube, CodeQL, ESLint, Checkstyle, PMD, SpotBugs, ...) with judgement instead of blind auto-fix. For every open finding: read the code, give it a verdict — real / false positive / accepted risk — and either propose a minimal fix or write down why it isn't worth fixing. Stack-agnostic. Invoke with `/sdlc-ia:debt-triage [rule id or file glob]`.
model: opus
disable-model-invocation: true
argument-hint: "[rule id or file glob]"
---

# debt-triage — Judge the Backlog, Don't Bulldoze It

You are triaging **already-reported** findings from a static analyzer the repo runs today — not
running a new scanner, and not fixing the codebase's whole surface at once. Every finding gets a
verdict and, when it's real, either a minimal fix or a filed issue — never a blind apply of the
tool's own suggested fix.

⚠️ **Mandatory — first steps:**

1. Call `EnterPlanMode`. Phases 1 to 3 run inside it: discovering the analyzer, pulling the
   findings and reaching a verdict on each one write nothing versionable, even when pulling means
   running the analyzer's own report target (`mvn checkstyle:check`, `eslint -f json`) and leaving
   a report under `target/` or `node_modules/`.
2. Do not leave plan mode and do not write a single fix, suppression or issue until the user
   approves the triage explicitly (Phase 4).
3. On Windows, activate the `windows-powershell` skill — if the session offers it — before the
   first non-trivial shell command. Quoting and encoding are the recurring traps, and every command
   this skill runs has to work the same in PowerShell 5.1, PowerShell 7 and bash.

## Philosophy

- **Judgement, not auto-fix.** A tool's suggested fix is a hint, not a patch to paste. Read the
  call site before agreeing with the rule.
- **Every finding gets a verdict, not a checkbox.** REAL, FALSE POSITIVE, or ACCEPTED RISK — each
  with one sentence of reasoning, in the report and next to any suppression.
- **Batch by rule, not by file.** The same rule firing 40 times usually shares one line of
  reasoning; re-deriving it 40 times wastes the run's own budget.
- **No fix without a test net.** A finding sitting in code with no test nearby is safer left as a
  filed issue than turned into a silent, unverified behavior change. It stays a *proposal* until
  the Phase 4 checkpoint, where the user decides with the missing net named out loud — never a fix
  this skill applies on its own judgement.
- **Never touch a finding the diagnostic can't explain.** If you cannot state in one sentence why
  the rule fired, that finding stays uncertain and gets a human flag — not a guess.
- **Suppressions carry their reason inline**, next to the code, not only in the report — the next
  reader of that line won't have this session's context.
- **Never commit.** Leave the diff for the user to review, same as every other skill in this
  plugin.

## Phase 1 — Discover the analyzer(s)

Find what already runs in this repo — do not install a new tool (that belongs to
`instrument-project-java`, not here). Check, in order: a CI workflow step naming
Sonar/CodeQL/ESLint/Checkstyle/PMD/SpotBugs/golangci-lint/ruff; a config file at the repo root
(`sonar-project.properties`, `.eslintrc*`, `checkstyle.xml`, `spotbugs-exclude.xml`,
`.github/codeql/*`); a `make`/npm/Maven target that runs one. If more than one is present, ask
which to triage via `AskUserQuestion` — don't silently pick one.

Resolve how findings are read back — a report file the CI step already writes, a CLI/API query, or
a dashboard — per `references/analyzer-detection.md`.

**If none of this discovers an analyzer that reports a triageable backlog, that is a real, reportable
outcome — not a silent no-op.** Stop here and tell the user, explicitly:

- What was checked and found absent (name the CI steps, config files and build targets actually
  looked for, not a generic "no analyzer found").
- What already runs as **prevention** in this repo, if anything — a strict-mode compiler flag or
  Checkstyle in blocking mode (installed by `instrument-project-java`) stops *new* violations from
  landing, but produces no backlog of existing findings to triage. Say this distinction plainly:
  prevention is not triage, and a repo can have one without the other.
- What connecting a real analyzer (SonarQube, CodeQL, or whichever fits the stack) would take, so
  the next run has something to work with — but do not install one; that decision and that
  installation both belong to `instrument-project-java`, invoked separately, not to this skill.
  On a Java/Maven repository, name them: **control 13** is SonarQube (and needs a server the team
  already runs), **control 11c** is CodeQL (free on a public repository, paid on a private one).
  Point at the control, not at a vague "set up an analyzer" — the difference between the two is
  whether the user knows what it will cost them before they start.

Do not exit silently and do not fabricate findings to have something to report.

## Phase 2 — Pull and group

Pull the open findings and group by rule id. For each group, record: rule id, severity, count, and
one example location.

🚫 **STOP — show the grouped list and wait**, before triaging any single finding: the user may want
to scope this run to one severity, one module, or one rule, and triaging the whole backlog first
spends the run's budget on findings nobody asked about.

## Phase 3 — Triage each group

Read the flagged code and its immediate caller/callee, then decide — worked examples per verdict in
`references/verdict-criteria.md`:

- **REAL** — the rule is right. Propose a minimal, targeted fix.
- **FALSE POSITIVE** — the rule fired on code that is correct in this context. Propose an inline
  suppression with the one-sentence reason.
- **ACCEPTED RISK** — the rule is right but fixing it now costs more than the risk it flags.
  Suppress with the reason, or file an issue if the repo tracks debt that way — never leave a
  finding un-triaged.

Never batch-apply a fix across a whole rule group without reading each call site — the same rule
can be REAL in one file and a FALSE POSITIVE three lines away.

## Phase 4 — Propose, get approval, then write

Present the triage as a plan — one row per finding with its verdict and the action it would take:
the minimal fix, the suppression and its reason, or the issue to file. Mark every REAL finding
whose surrounding code has **no test covering the changed behavior**; that mark is the whole point
of the checkpoint, because it is where the user, not this skill, decides between a fix nobody can
verify and an issue.

If actually resolving a finding would require touching code the finding didn't flag, don't expand
the diff silently — carry it into the plan as a separate, scoped follow-up.

🚫 **STOP — nothing is written until the user approves this plan explicitly.** Only after the yes:
`ExitPlanMode`, then write the approved rows and nothing else. A finding the user struck from the
plan stays un-actioned and is reported as such; it does not quietly become a suppression.

## Phase 5 — Gate, then report

The run is **not** triaged until every row of the gate holds. Everything below the rule is
informative — useful to read, never a reason to pass or fail:

| Criterion | |
|---|---|
| Every open finding in scope carries one of the three verdicts — none left un-triaged | gate |
| Every suppression carries its one-sentence reason **inline**, next to the code | gate |
| Every applied fix was read at its own call site, not batch-applied across a rule group | gate |
| Every fix written is one the Phase 4 plan listed and the user approved | gate |
| No REAL fix landed on untested code without that being named in the plan the user approved | gate |
| Nothing committed | gate |
| — | |
| Count per verdict, and which rule groups dominate the backlog | informative |
| Severity mix, and how much of the backlog this run covered | informative |

If a row fails, go back to Phase 3 for the findings it names and fix the run — do not report it as
triaged with a caveat.

**Report.** One table: finding (rule id + location) → verdict → action taken (fixed / suppressed
with reason / filed as issue / not actioned by the user's choice) → why. Close with the informative
counts, so the user sees the shape of the backlog and not just the list.

## Rules

- Do NOT apply a tool's auto-fix suggestion without reading the call site yourself.
- Do NOT suppress a finding without a reason written next to the suppression.
- Do NOT expand a fix beyond what the finding flagged — file a follow-up instead.
- Do NOT run a new analyzer the repo doesn't already have configured.
- Do NOT write a fix, a suppression or an issue before the Phase 4 approval — Phases 1 to 3 are
  plan mode.
- Do NOT report a run as triaged while any gate row of Phase 5 fails.
- Do NOT exit silently when Phase 1 finds no analyzer — report what was checked, what prevention
  (if any) already runs, and what connecting a real one would take.
- Do NOT commit.
