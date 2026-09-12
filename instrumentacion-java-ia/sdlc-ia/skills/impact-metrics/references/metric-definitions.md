# The four metrics: two shipped, two with their reason

Every command below was **run against a live repository** on 2026-09-11. The numbers in the worked
examples are that repository's real output, not illustrations.

## Why only two ship

Not difficulty — all four come from git and `gh`. **Certainty of the definition.**

Counting commits carrying a marker is counting, and "from opened to merged" means the same to
everyone. The other two require agreeing on something first, and a first report carrying one
disputable number discredits the other three: the meeting becomes about methodology instead of
about the work.

---

## Metric 1 — Percentage of AI-assisted pull requests

Three commands, **identical in Windows PowerShell 5.1, PowerShell 7 and bash**: git only, no pipes,
no `&&`, no command substitution.

```
git fetch origin

# Total pull requests integrated in the period
git rev-list --count origin/dev --first-parent --since=2026-07-01 --until=2026-10-01

# Of those, the AI-assisted ones
git rev-list --count origin/dev --first-parent --since=2026-07-01 --until=2026-10-01 --grep="^Asistido-por-IA: "
```

**`--first-parent` is not optional.** The integration branch receives by squash, so one first-parent
entry equals one pull request. Without the flag you count every reachable commit — on the worked
repository, 93 instead of 26.

**Why `--grep` and not git's trailer reader.** The "correct" form would be `--format` with
`%(trailers:key=Asistido-por-IA,valueonly)`, and on a squash-merged history it **returns empty for
every commit**. Git only parses the **last contiguous block** of the message as trailers, and
**GitHub rewrites the message when it squashes**: it separates each trailer with a blank line and
moves `Co-authored-by` last, so that final block is that one line and nothing else.

**This is not a formatting mistake anyone can avoid.** The commits that produced the history below
were written with the footer as a single contiguous block; GitHub broke it apart at merge time. So
`--grep` is not a workaround waiting for a fix — it is the only correct way to read this marker out
of a squash-merged branch. Verified over 109 first-parent commits of this monorepo:

| Method | Result |
|---|---|
| `%(trailers:key=Asistido-por-IA,valueonly)` | **0** |
| `--grep="^Asistido-por-IA: "` | **23** |

**Anchor the pattern — `^Asistido-por-IA: `, not the bare string.** An unanchored `--grep` also
matches any commit that merely *mentions* the marker in prose: a commit documenting this very
behaviour would count as AI-assisted, inflating the metric. Proven on a two-commit probe — one
carrying the real trailer, one only discussing it: unanchored counts 2, anchored counts 1. On this
monorepo today both return 23, because no commit mentions it in prose yet; that is exactly why it
is worth anchoring **before** one does.

**Worked example**, September 2026: 26 integrated, 9 marked → **35 %**.

**One number, chosen once.** The API gives a different figure for the same month (11 instead of 9)
because it also counts release pull requests into the stable branch. Both are defensible;
**pick one, document it, never switch** — mixing them between periods breaks the comparison, which
is the only thing the metric is for.

**Zero is not a result.** A repository that has been running the loop and reports `0 %` is telling
you the marker is not being written, not that no pull request used AI. Say which.

---

## Metric 2 — Lead time

One command. The whole computation lives inside `--jq`, so there is no shell code to translate.

```
gh pr list --state merged --search "merged:2026-07-01..2026-09-30" --limit 500 --json createdAt,mergedAt --jq '[.[] | ((.mergedAt|fromdateiso8601)-(.createdAt|fromdateiso8601))/3600] | sort | {n: length, mediana: (.[length/2|floor]|.*100|round/100), promedio: ((add/length)*100|round/100), max: (.[-1]|.*100|round/100)}'
```

**Real output:**

```json
{"max":172.27,"mediana":14.04,"n":31,"promedio":62.61}
```

**Report the median.** Here is why, in one line of evidence: 14 hours median against 62 mean,
because a single 172-hour pull request drags the average. The mean describes the worst case dressed
as the typical one.

### The quoting rule that breaks PowerShell 5.1

Inside a `--jq` expression there can be **no quotes** — PowerShell 5.1 strips them and jq receives
something that will not compile. That is why the expression above contains none: it is all field
access and function calls.

Wrapping the whole expression in single quotes is fine. Leaving it unwrapped is not: PowerShell
takes the pipes as its own and fails with *"Expressions are only allowed as the first element of a
pipeline"*.

---

## Metric 3 — Rework: the definition, written now, measured later

The data exists. What does not is an agreed definition. "Rework" can be at least five measurable
things — commits that re-touch files from a recent pull request, extra review rounds, reverts,
fix-up pull requests referencing an earlier one, review comments demanding changes. All come from
`gh`, all measure something different, and **changing the definition later breaks the comparison
with the previous period**.

So it is agreed now and measured when there is sample:

> **Fix-up or revert pull requests referencing a pull request integrated in the last 14 days.**

Closest to "something went wrong with what we shipped", and it comes from `gh`.

**Why it waits.** The metric compares assisted against unassisted. With 26 pull requests a quarter
and nine assisted, that is groups of nine and seventeen over a noisy measure: the number moves on
its own between quarters with nothing having changed, and gets read as signal. **The most important
of the four is the one that behaves worst with a small sample** — shipping it early would not be
bold, it would be misleading.

---

## Metric 4 — Coverage: conditional, not missing

Read it from the JaCoCo report that control 10 of `/sdlc-ia:instrument-project-java` produces:

```
target/site/jacoco/jacoco.xml
```

**If the repository has it**, fold it in. **If it does not**, the metric is reported as missing with
its reason — control 10 is not installed — and the remedy named. Not as a zero: a repository with no
coverage instrumentation has unknown coverage, and those are different facts.

Note in the report that control 10's rule is scoped to **new code**, so the repo-wide number and the
gate are measuring different things. Presenting one as the other invites a conversation nobody
wants.

---

## The aggregate

Written before any prose, so the next period compares against a file and not a paragraph.

```
docs/metricas/metricas-2026-Q3.csv
metrica,valor,n,fuente
pct_prs_con_ia,35.0,26,git rev-list --first-parent --grep
lead_time_mediana_h,14.04,31,gh pr list createdAt/mergedAt
retrabajo,,,pendiente v2 — definicion acordada, falta muestra
cobertura,,,pendiente — control 10 no instalado
```

The two missing rows stay, with their reason. A report that silently omits them says the team
decided they did not matter; one that names them says what is not known yet, which is the honest
claim.
