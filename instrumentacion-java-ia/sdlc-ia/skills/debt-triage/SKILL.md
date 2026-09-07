---
name: debt-triage
description: Triage findings already reported by whatever static analyzer the repo runs today (SonarQube, CodeQL, ESLint, Checkstyle, PMD, SpotBugs, ...) with judgement instead of blind auto-fix. For every open finding: read the code, give it a verdict — real / false positive / accepted risk — and either propose a minimal fix or write down why it isn't worth fixing. Stack-agnostic. Invoke with `/sdlc-ia:debt-triage [rule id or file glob]`.
disable-model-invocation: true
argument-hint: "[rule id or file glob]"
---

# debt-triage — Judge the Backlog, Don't Bulldoze It

You are triaging **already-reported** findings from a static analyzer the repo runs today — not
running a new scanner, and not fixing the codebase's whole surface at once. Every finding gets a
verdict and, when it's real, either a minimal fix or a filed issue — never a blind apply of the
tool's own suggested fix.

## Philosophy

- **Judgement, not auto-fix.** A tool's suggested fix is a hint, not a patch to paste. Read the
  call site before agreeing with the rule.
- **Every finding gets a verdict, not a checkbox.** REAL, FALSE POSITIVE, or ACCEPTED RISK — each
  with one sentence of reasoning, in the report and next to any suppression.
- **Batch by rule, not by file.** The same rule firing 40 times usually shares one line of
  reasoning; re-deriving it 40 times wastes the run's own budget.
- **No fix without a test net.** A finding sitting in code with no test nearby is safer left as a
  filed issue than turned into a silent, unverified behavior change.
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

Do not exit silently and do not fabricate findings to have something to report.

## Phase 2 — Pull and group

Pull the open findings and group by rule id. For each group, record: rule id, severity, count, and
one example location. Show the grouped list before triaging any single finding — the user may want
to scope this run to one severity, one module, or one rule.

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

## Phase 4 — Propose, don't force

For each REAL finding, write the fix. If the surrounding code has no test covering the changed
behavior, say so explicitly and keep the change as small as possible — do not refactor beyond the
finding's own scope.

If actually resolving a finding would require touching code the finding didn't flag, stop and
propose that as a separate, scoped follow-up instead of expanding the current diff silently.

## Phase 5 — Report

One table: finding (rule id + location) → verdict → action taken (fixed / suppressed with reason /
filed as issue) → why. Close with a one-line count per verdict so the user sees the shape of the
backlog, not just the list.

## Rules

- Do NOT apply a tool's auto-fix suggestion without reading the call site yourself.
- Do NOT suppress a finding without a reason written next to the suppression.
- Do NOT expand a fix beyond what the finding flagged — file a follow-up instead.
- Do NOT run a new analyzer the repo doesn't already have configured.
- Do NOT exit silently when Phase 1 finds no analyzer — report what was checked, what prevention
  (if any) already runs, and what connecting a real one would take.
- Do NOT commit.
