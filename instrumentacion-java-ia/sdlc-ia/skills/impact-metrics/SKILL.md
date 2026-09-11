---
name: impact-metrics
description: Measure what AI-assisted delivery actually changed and write the report leadership reads — percentage of AI-assisted pull requests and lead time from the repository's own history, coverage when the repo has it, plus an anonymous four-question survey the team owns. Ships two metrics, not four, because only two have a definition nobody argues with; the rest are listed with the reason they are missing. Generates and opens the pull request — it never sends anything. Invoke with `/sdlc-ia:impact-metrics`.
disable-model-invocation: true
---

# impact-metrics — Numbers Leadership Can Act On

The Fase 5 of the method, and the box the rest of the backlog waits behind: two of the four
advancement criteria in Fase 6 are evaluated with these numbers.

You produce three artifacts: the **aggregate** (`docs/metricas/metricas-<periodo>.csv`), the
**survey aggregate** the team fed in, and the **report** (`docs/metricas/reporte-<periodo>.md`).
Then you open the pull request and stop.

## Philosophy

- **Ship the metrics whose definition nobody argues with.** Two, not four. A first report carrying
  one disputable number discredits the other three, and the meeting becomes about methodology.
- **A missing metric is reported as missing, with its reason.** Never omitted — an omission reads
  as "it did not matter".
- **Median, not mean, for anything time-shaped.** One 172-hour pull request drags a mean into
  describing a worst case as if it were typical.
- **Generate, never send.** No skill in this package merges, deploys, or sends a real
  communication — `github-plan-build` escalates on exactly that. A leadership report is one.
- **The survey's raw answers never enter git.** Only the aggregate does. Stripping the name is not
  anonymity: with a small team, the *pattern* of four answers identifies a person, and git is
  permanent.
- **Below the agreed sample floor, publish nothing.** With three responses a distribution points at
  someone as surely as a name does.
- **Compare against the previous period or say you cannot.** A number with nothing to compare it to
  is a fact, not a finding.
- **Everything you write is in the repository's documentation language**, matching the files you
  edit.

---

## Phase 1 — Discover (silent)

```
gh auth status
git remote get-url origin
git log origin/<integration> --first-parent -1
gh repo view --json nameWithOwner,defaultBranchRef
```

Establish:

1. **The integration branch**, from `AGENTS.md`/`CLAUDE.md`, falling back to the remote default.
   Everything is counted on its first-parent history.
2. **The period.** Ask if it was not given. Default: the last full quarter.
3. **Whether the AI marker is present at all** — `git rev-list --count <branch> --first-parent
   --grep=Asistido-por-IA`. A zero on a repository that has been using the loop means the marker is
   not being written, not that no PR used AI: say which, do not report `0 %`.
4. **Coverage availability** — does `target/site/jacoco/jacoco.xml` exist, or a JaCoCo plugin in the
   POM? If not, coverage is a missing metric with a named reason
   (`/sdlc-ia:instrument-project-java` control 10), not a zero.
5. **The survey aggregate** for the period, if any.
6. **The previous period's files**, for the comparison.

---

## Phase 2 — Prerequisites

| Check | Why |
|---|---|
| `gh auth status` | Lead time comes from the API |
| A local clone with the integration branch fetched | The first metric is computed locally, from git |

Nothing to install. Both metrics come from tools the repository already requires.

---

## Phase 3 — Measure

Follow `references/metric-definitions.md`, which carries every command, verified against a live
repository, and the reasoning for each. In short:

| Metric | Source | v1 |
|---|---|---|
| % of AI-assisted pull requests | `git rev-list --count` with `--first-parent --grep` | **yes** |
| Lead time (median) | `gh pr list` with the whole computation inside `--jq` | **yes** |
| Rework | — | no: its definition is written and waiting for sample size |
| Coverage | JaCoCo report, when the repo has one | conditional |

Both shipped commands are **identical in Windows PowerShell 5.1, PowerShell 7 and bash**. Do not
introduce a shell pipeline to post-process them.

Write the aggregate before writing any prose — the report is derived from the file, never the other
way around, so the next period can be compared against a file rather than against a paragraph.

---

## Phase 4 — The survey

The team's, not the skill's. What this skill owns is the questionnaire, the anonymity requirement
and the aggregation format; **capture belongs to whatever form tool the team already has.**

Hand over `templates/<lang>/encuesta-cualitativa.md.template` — it carries the four questions and
the full procedure, including which setting in each common form tool has to be turned off. Then:

- **If the aggregate for this period exists**, read it and fold it into the report.
- **If it does not**, the report says "no survey this period" and why. Do not invent, do not
  interpolate from last period, and do not quietly drop the qualitative half — the four hard numbers
  can all improve while the team is having a worse time, which is the entire reason it exists.
- **If the sample is below the agreed floor**, report "insufficient sample" and publish no
  distribution.

---

## Phase 5 — Write the report

From `templates/<lang>/reporte.md.template`: diagnosis, metrics compared against the baseline, and
the commitment for the next period.

**Every number carries where it came from.** A report whose figures cannot be recomputed is an
anecdote with numbers in it.

If there is no previous period, say so in the report and label this one the **baseline** — that is
the Etapa 1 advancement criterion, and stating it plainly is more useful than an invented trend.

---

## Phase 6 — Open the pull request, and stop

```
git switch --create docs/metricas-<periodo> origin/<integration>
git add docs/metricas/
git commit -m "docs: metricas de <periodo>"
git push --set-upstream origin docs/metricas-<periodo>
gh pr create --base <integration> --title "docs: metricas de <periodo>" --body "<resumen>"
```

Two commands per step, no `&&`, so it works in Windows PowerShell 5.1 too.

**Then stop. Sending it is a person's job.** Not an email, not a Slack message, not a Discussion.
The pull request is not ceremony: it puts the numbers in front of someone before they leave the
team, and it gives you a permanent link, which is what actually gets shared.

Report: each metric with its value, its `n` and its source command; each missing metric with its
reason; the survey's state; and the pull request URL.

---

## Reference

- `references/metric-definitions.md` — every command, verified, and why each metric is in or out.
- `templates/<lang>/encuesta-cualitativa.md.template` — the survey and its procedure.
- `templates/<lang>/reporte.md.template` — the report skeleton.

## Rules

- Do NOT report four metrics when two are measurable. List the others as missing, with the reason.
- Do NOT report a mean for lead time. Median, and say the mean only alongside it.
- Do NOT let a single raw survey response reach git. The aggregate, or nothing.
- Do NOT publish a distribution below the agreed sample floor.
- Do NOT report `0 %` when the marker is absent from the history — that is a broken measurement,
  not a measurement of zero.
- Do NOT send the report anywhere. Open the pull request and stop.
- Do NOT invent a comparison when there is no previous period. Call it the baseline.
