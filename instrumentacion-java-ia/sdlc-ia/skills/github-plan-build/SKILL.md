---
name: github-plan-build
description: >
  Take a GitHub issue from "read it" to "PR open, CI green, review comments
  addressed, issue updated" with maximum autonomy. Reads the issue and its
  discussion via the `gh` CLI; grills you on the design decisions the issue left
  open; explores the repo; drafts a plan and puts it through a three-lens
  adversarial review; asks for your approval through plan mode when the change
  warrants it and skips it when it doesn't; then implements test-first, runs your
  repo's own gates, opens the pull request, and babysits it to green. Stack-agnostic
  — assumes no particular architecture.
  Invoke with `/sdlc-ia:github-plan-build [issue number or URL] [skip-checkpoint]`.
argument-hint: "[issue number or URL] [skip-checkpoint]"
disable-model-invocation: true
allowed-tools: Read, Glob, Grep, Edit, Write, AskUserQuestion, Agent, Skill, TaskCreate, TaskUpdate, TaskList, TaskGet, EnterPlanMode, ExitPlanMode, Monitor, ScheduleWakeup, Bash(git status*), Bash(git diff*), Bash(git add*), Bash(git commit*), Bash(git log*), Bash(git rev-parse*), Bash(git symbolic-ref*), Bash(git fetch*), Bash(git checkout*), Bash(git switch*), Bash(git pull*), Bash(git push*), Bash(gh auth status*), Bash(gh repo view*), Bash(gh label list*), Bash(gh issue*), Bash(gh pr*), Bash(gh api*), Bash(gh run*), Bash(gh project*), Bash(make *), Bash(npm *), Bash(npx *), Bash(pnpm *), Bash(yarn *), Bash(pytest*), Bash(python *), Bash(python3 *), Bash(uv *), Bash(go *), Bash(cargo *), Bash(dotnet *), Bash(mvn *), Bash(gradle *), Bash(./gradlew*), Bash(bundle *), Bash(rake *), Bash(composer *), Bash(php *)
---

# GitHub issue → shipped feature

Take a GitHub issue all the way to **PR open, CI green, review comments addressed,
and the issue updated**. There is exactly **one** explicit checkpoint — plan
approval — and even that is conditional: routine changes run straight through.

It is **stack-agnostic** — a Java/Spring repository is one case it handles, not what
it assumes.

**Read `references/build-loop.md` now** (Steps A–E; F–J continue in `build-loop-execute.md`,
read at Step F) — the body of this skill, not optional background. This file supplies the GitHub
bindings both ask for.

## The 9 bindings, resolved for GitHub

`references/build-loop.md` never names a tracker; it only asks for these nine bindings. GitHub is
the simplest tracker this method covers — no MCP prefix ambiguity (Linear) and no
process-dependent state machine (Azure DevOps' Basic/Agile/Scrum). A single CLI, `gh`, covers
every binding:

| Binding | GitHub |
|---|---|
| `TICKET` | `gh issue view <n> --json ...` (full field list in Phase 1) — includes `parent`/`subIssues`/`subIssuesSummary`, the sub-issue relation Step A already asks for generically ("its parent and subissues"); an issue with none just returns empty |
| `STATUS→IN-PROGRESS` / `STATUS→IN-REVIEW` | GitHub has no native status field. Phase 1 detects whether the repo uses **labels** or **Projects v2** and resolves accordingly — see below. |
| `COMMENT` | `gh issue comment <n> --body "<text>"` |
| `BRANCH` | `feature/<n>-<short-slug>` — same pattern as the other delivery skills, branch name **must** contain the issue number |
| `LINK-TOKEN` | `Closes #<n>` / `Fixes #<n>` / `Resolves #<n>` — GitHub's native auto-close syntax, placed in the commit message or the PR body |
| `OPEN-PR` | `gh pr create --title "<title>" --body "<body with Closes #<n>>"` |
| `CI` | `gh pr checks <pr> --watch` to wait; `gh run view --log-failed` for logs; `gh run rerun --failed` to retry a flaky job |
| `PR-COMMENTS` | `gh pr view --comments` for the conversation; `gh api repos/{owner}/{repo}/pulls/{n}/comments` for inline review threads |

No separate `references/github-access.md` exists — unlike Azure DevOps or Linear, GitHub has no
dual access path or ambiguous MCP prefix to discover at runtime; this skill talks to GitHub
exclusively through `gh`. Add that file if a future session needs an MCP path — don't build it
speculatively.

## `CHILD-LINK` — decomposing a ticket into sub-issues

Not one of `build-loop.md`'s original nine — an addition this plugin makes for a `TICKET` that
already has GitHub's native sub-issues attached (typically written by
`requirement-to-spec-java`'s tracker mode, or by hand). Confirmed against `gh` 2.98.0, not a
speculative convention: `gh issue create --parent <n>` and
`gh issue view --json subIssues,subIssuesSummary,parent` are both real, current commands.

Resolved for GitHub, mirroring the same "each child commit carries the child's own id; the PR is
opened with the parent's" split `arkandia-skills` upstream already uses for its own Azure DevOps
and Linear delivery skills (`ado-plan-build`/`linear-plan-build`, since their 0.5.0) — this plugin
has no such siblings of its own, GitHub is the only tracker it talks to, but the split is a real,
already-shipped pattern, not one invented here.

| Step | GitHub |
|---|---|
| Read children | The `TICKET` fetch returns only child numbers/titles/state via `subIssues`, not their bodies — Phase 1 already requires one further `gh issue view <child> --json title,body,state` per child, a real separate call, before Step A |
| Per-child commit | `Refs #<child>` in the commit message that finishes that child's steps (see `build-loop-execute.md` Step F.1 and Step H.2) |
| Child completion | `gh issue close <child> --comment "<summary>"` — held until Step I confirms CI green **and** every review comment is addressed, not fired the moment the child's commit lands. This skill's own Phase 3 rule ("issue state and labels are not authoritative") cuts both ways: closing #101 right after its commit, only to have CI fail or a reviewer request changes to that same code in Step I, leaves a sub-issue marked done while its code is still unmerged and possibly still being fixed |
| Parent | Still resolved with `LINK-TOKEN` (`Closes #<parent>`) on the commit that finishes the **last** child, and `OPEN-PR` still opens exactly one PR against the parent — a decomposed ticket is still one branch, one PR |

A `TICKET` with no sub-issues is unaffected — every step above is conditional on
`subIssuesSummary.total > 0`, checked once in Phase 1 when `TICKET` is first read.

## `PLAN-PERSIST` — where the approved plan lives afterward

Resolved **by repo convention, not by asking**: if `docs/plans/` already exists in the target
repo, Step E's `PLAN-PERSIST` step copies the vetted plan to `docs/plans/<n>-<short-slug>.md` and
stages it — commit it together with the first Step H commit, not as a separate one — so the plan
survives as versioned evidence, the same pattern the manual `manuales/manual-feedback-chat`
demonstrates for issue #3 (`docs/plans/plan-issue-3-feedback-chat.md`, PR #6).

If `docs/plans/` does **not** exist, `PLAN-PERSIST` is a no-op: the plan already lives in the
approval checkpoint's own record from `EnterPlanMode`/`ExitPlanMode`, which is not part of the
repository and needs nothing further written. That is the default for any repo that has not
opted into the versioned convention — not a gap, a choice this skill respects rather than
overrides.

## Autonomy contract

- **Act and self-verify by default.** No option menus, no "should I proceed?" on green.
- **Issue writes on this issue are pre-authorized** — labels, status, and comments of
  `TICKET`. Never ask permission for those, and never write anything else in the tracker.
- **This skill pushes branches and opens pull requests without asking.** It never merges a PR,
  never enables auto-merge, and never deploys. If that is more autonomy than you want on a given
  issue, run it without `skip-checkpoint` and stop it at the Step E checkpoint.
- **Escalate only** for the cases listed in **Escalation** below.

## Arguments

Parse `$ARGUMENTS`:

- **Issue number or URL** — bare digits (`42`), digits with a leading `#` (`#42`), or
  a `github.com/<owner>/<repo>/issues/<n>` URL (extract the number and, if present,
  the owner/repo — otherwise resolve owner/repo from the git remote). If absent, ask
  for one.
- **`skip-checkpoint`** — or freeform "skip the plan checkpoint" / "run straight to
  PR". The user's opt-in for routine issues: force the Step E skip. Honor it only
  when explicitly given, and never over a user who asked to see a plan.

There is no brief-file or inline-description path. This skill starts from a GitHub
issue; if you don't have one, ask for one.

## Phase 0 — Resolve access

Confirm the `gh` CLI is authenticated: `gh auth status`. If it fails, stop and report
the exact error — point the user at `gh auth login`. Never guess an issue's content
from its number.

Confirm the target repository: `gh repo view` from the working tree resolves
owner/repo from the git remote by default. If the issue URL names a different
owner/repo than the checkout, say so and ask which one to operate against —
don't silently operate on the wrong repo.

## Phase 1 — Read the issue and resolve the STATUS binding

1. Fetch the issue: `gh issue view <n> --json title,body,state,labels,assignees,milestone,comments,url,parent,subIssues,subIssuesSummary`.
   **Requirements are frequently negotiated in comments rather than written in the
   body** — read them before concluding anything is unspecified. Note any linked or
   referenced issues (task-list checkboxes or `#<n>` mentions in the body) and read
   those too if they look like blockers.

   **If `subIssuesSummary.total > 0`, this ticket is decomposed — read every child
   too** (`gh issue view <child> --json title,body,state`) before Step A. `CHILD-LINK`
   (below the bindings table) governs how each child gets its own commit and its own
   completion from here on; a ticket with no sub-issues skips it entirely, unaffected.

2. **Resolve `STATUS→IN-PROGRESS` / `STATUS→IN-REVIEW` before any status write.**
   GitHub has no built-in issue status, so detect which mechanism this repo actually
   uses:

   - **Labels** — `gh label list` for names matching an in-progress/in-review
     convention (`in progress`, `in-progress`, `status: in review`, `in-review`, …).
   - **Projects v2** — `gh project list --owner <owner>` for a project the issue
     belongs to, then check whether it has a single-select `Status` field with
     matching options (`gh project item-list` / `gh api graphql` to read the field
     and its options — discover the exact query before relying on it, don't guess a
     field ID).

   Resolve by what you found:
   - **Only labels exist** → bind to `gh issue edit <n> --add-label "<label>"`
     (removing the prior status label if the convention is single-label).
   - **Only Projects v2 exists** → bind to
     `gh project item-edit --id <item> --field-id <status-field> --single-select-option-id <opt>`.
   - **Both exist** → ask the user once via `AskUserQuestion` which one is the
     source of truth for this repo; don't write to both.
   - **Neither exists** → ask the user, or skip the status-write steps silently and
     say so in the Phase 3 summary — document that both mechanisms were checked and
     neither was found, so "skipped" isn't mistaken for "forgotten".

   Say which mechanism you resolved to, once, and reuse it for the rest of the run.

## Phase 2 — Prepare the git environment

1. Default branch: `git symbolic-ref refs/remotes/origin/HEAD | sed 's@^refs/remotes/origin/@@'`.
2. `git fetch origin`.
3. **If the working tree is dirty, stop and report.** Do not stash, do not discard.
4. `git checkout <default-branch> && git pull --ff-only origin <default-branch>`.
5. Check for an in-flight sibling: `gh pr list --state open --json number,headRefName,files`.
   If an open PR's files overlap the ticket's area, say so and ask via `AskUserQuestion`
   whether to branch from it instead — don't block, don't assume.
6. Create `feature/<n>-<short-slug>`. The branch name **must** contain the issue
   number. If you are already on that branch with prior work on it, stay on it and
   continue rather than recreating it.

## Phase 3 — Present the issue summary

Print a concise summary: title, state, labels, assignees, milestone, branch name,
linked/referenced issues, the STATUS mechanism (or why it was skipped), and the decisions buried
in the comments.

**Issue state and labels are not authoritative.** Flag every referenced blocker
that isn't closed, and before treating a dependency as met, confirm it against
`git log` and the code rather than against a label.

## Phase 4 — Run the build loop

Follow Steps A → J across `references/build-loop.md` and `references/build-loop-execute.md`, with
the bindings resolved above.

## Escalation

Stop and ask **only** for:

- **Production writes, deploys, or destructive / irreversible actions.**
- **Customer-facing sends** — real outreach to real recipients.
- **An ambiguous gate or CI failure** — a check fails in a way you cannot
  confidently resolve: flaky vs. real, unclear error, environment gap, possibly
  pre-existing.
- **A non-converging watch loop** — CI still red after 3 fix attempts on the same
  job.
- **A product-judgment call with no source-of-truth answer** that Step A did not
  close.
- **A missing credential or permission** you would have to work around.

For everything else — branching, planning, implementing, fixing your own gate
failures, and issue writes on `TICKET` itself — decide and proceed. Do not check in.

## Notes

- **Keep secrets out of the shell and the commit.** Don't stage `.env` files, keys,
  or tokens, and don't echo secret values into commands, commit messages, or PR
  bodies.
- **Gate commands.** The mainstream runners (`make`, `npm`/`pnpm`/`yarn`, `pytest`,
  `go`, `cargo`, `dotnet`, `mvn`/`gradle`, `bundle`, `composer`) are pre-approved. If
  your repo's gate isn't among them, run it and approve the prompt — never skip or
  fake a gate to avoid a permission dialog.
