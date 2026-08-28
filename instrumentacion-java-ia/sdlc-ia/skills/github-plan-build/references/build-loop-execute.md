# The build loop — implement to merged-ready (Steps F–J)

> Continues `references/build-loop.md` (Steps A–E: grill, explore, plan, adversarial review,
> approval checkpoint) for the `github-plan-build` skill. Same bindings, same tracker-agnostic
> scope. Only Step E (in the other file) pauses for the user — everything here is **act and
> self-verify**; see **Escalation** below for the complete list of things that stop you.

## Step F — Implement, test-first

1. Break the plan into 3–8 concrete steps with `TaskCreate`; mark each `in_progress` /
   `completed` as you go. State lives in the task list, not in memory. **If `TICKET`
   has sub-issues, group these steps by the child they belong to first** — the file
   partitioning in step 2 still decides what runs serially versus in parallel *within*
   a child's steps, but a step never spans two children: `CHILD-LINK`'s per-child
   commit only makes sense if each commit's steps trace back to one sub-issue.

2. **Partition the steps by the files they touch.** This decides what can fan out, and
   it is the whole judgment call:

   - Steps whose file sets are **disjoint** (a new validator in one module, an
     unrelated fix in another, a config change) can run as **parallel subagents**, one
     step each, dispatched in a single message.
   - Steps that **converge on the same file** stay **serial**, in one agent. This is
     the common case: N acceptance criteria usually become N checks in the *same*
     function plus N tests in the *same* test file. Two agents editing that file in
     parallel will overwrite each other — and a format-on-save hook, if the repo has
     one, makes the race worse, not better.

   When steps converge you can still parallelize the *thinking*: fan out one subagent
   per acceptance criterion to **return a proposed test and change as a diff, writing
   nothing**, then apply them yourself, serially, RED → GREEN. You get the breadth
   without the write conflict. Say which mode you chose and why. Only reach for
   `isolation: "worktree"` if agents genuinely must write the same paths concurrently;
   usually they shouldn't, and the merge cost is not worth it.

3. For each step, **RED → GREEN**: write the failing test first, run it and watch it
   fail, then write the minimal code to pass. After each step run the **targeted**
   check (a single-test filter and/or the linter), not the whole suite yet. Subagents
   report results; **you** run the checks, so one agent's green is never taken on
   faith.

4. Keep steps small — split anything past ~8 files / ~200 lines of diff. Follow the
   repo's own file-size and module-splitting conventions if it has any; do not impose
   a limit it never asked for.

5. On **3 consecutive failures of the same check**, stop iterating blindly. Classify
   the cause — test, code, environment, or plan drift — fix it at the source, and
   resume. If you can't confidently classify it, that's an escalation.

## Step G — Gates

**Resolve the repo's gate commands in this order**, and use the first that answers:

1. A "Gates", "Commands", or "Build & Test" section in `CLAUDE.md` / `AGENTS.md` —
   where a repository is supposed to declare this. Claude Code reads `CLAUDE.md`; many
   repos put the detail in `AGENTS.md` and delegate to it from there, so check both.
2. Fallback detection from the manifest — `Makefile`, `package.json`,
   `pyproject.toml`, `go.mod`, `Cargo.toml`, `*.sln`, `pom.xml`, `build.gradle`,
   `composer.json`, `Gemfile`. Read the actual scripts/targets; don't guess that
   `npm test` exists because `package.json` does.
3. If neither answers and the repo clearly has checks you can't identify, **ask** —
   one `AskUserQuestion` naming the candidates you found beats inventing a command or
   declaring an untested repo green.

Run every gate that resolves: lint, type-check, build, tests, and any architecture or
dependency lint the repo defines. **A gate that does not exist degrades to green — but
name the ones you skipped**, so "green" is never mistaken for "complete".

Then run **`/code-review`** at medium effort, review only (no `--fix`), scoped to
**this ticket's actual diff**: pass the branch's real starting point
(`git merge-base HEAD <base-branch>`), not the target `/code-review` would pick on its
own. A branch built on top of another PR, or carrying accumulated history, makes the
default comparison far wider than the ticket — diluting the review exactly where it
needs to be sharpest. Treat correctness and security findings as red. Run
**`/security-review`** as well when the diff touches auth, secrets, input parsing, or
external I/O.

**The gate is never delegated to a subagent.** Run it yourself and paste the real
output. A subagent reporting "tests pass" is a claim; the gate's own output is
evidence.

**Never proceed on red.** Fix and re-run this step. On an *ambiguous* failure — flaky
versus real, unclear error, possibly pre-existing — escalate rather than guess.

## Step H — Commit, push, open the PR

1. Stage **only the files you changed** — never `git add -A`, never anything
   secret-like (`.env`, keys, tokens), and never echo a secret value into a command or
   a commit message.
2. Commit with a message that includes `LINK-TOKEN` so the tracker attaches the commit
   to `TICKET`. **If `TICKET` has sub-issues and this commit finishes one of them**,
   the binding table's `CHILD-LINK` step (where defined) says how that commit
   references the child instead of the parent — but does **not** close it here. A
   child's own code is not proven until Step I says the PR is green with comments
   addressed; closing on the commit alone would mark a sub-issue done while CI can
   still fail it or a reviewer can still ask for changes to it. `LINK-TOKEN` on
   `TICKET` itself is still reserved for whichever commit finishes the **last**
   remaining child, or for a ticket with no children at all.
3. Push `BRANCH`.
4. Open the PR with `OPEN-PR`. The body links `TICKET`, summarizes the change, and
   lists **the verification commands you actually ran** with their results — not the
   ones you intended to run.

**Never merge the PR** and never enable auto-complete. Opening it is where your
authority ends.

## Step I — Watch CI to green, then address review comments

Loop until the PR is **green with no unaddressed comments**. This runs **in the main
conversation, not a fork** — it pushes commits and needs Bash approvals a subagent
cannot get. No one should need to watch the session while it runs.

**Do not tight-poll.** Prefer the platform's own blocking watch (see `CI`). Failing
that, pace the waits with the `Monitor` tool or `ScheduleWakeup` if this session
offers them; otherwise poll on a bounded schedule with real gaps between checks.

On red:

1. Pull the failing job's logs via `CI`.
2. Classify **flaky vs. real**. Rerun a plausibly-flaky job **once**. If it fails
   again, it is real.
3. Fix a real failure **at the source** — not by loosening the test. Re-run Step G
   locally, then push.
4. **Convergence guard:** three fix attempts on the same failing job and you stop and
   escalate. A loop that isn't converging is a signal, not a reason to keep going.

On green, read the review comments via `PR-COMMENTS` and address each one: change the
code, or reply explaining why not — never silently ignore one. Re-push, resolve the
threads the platform lets you resolve, and go back to watching CI. A comment that asks
for a product judgment nobody has answered is an escalation, not a code change.

**Once the loop exits — green, no unaddressed comments — and only then**, if `TICKET`
has sub-issues, this is where `CHILD-LINK`'s child-completion step runs: close each
child whose commit already landed. Not earlier, for the same reason Step H's commit
doesn't close them either — CI and review are what turn "committed" into "actually
correct," and a sub-issue closed before that point can still be wrong.

## Step J — Wrap up

1. Post the summary via `COMMENT` and set `STATUS→IN-REVIEW` (both pre-authorized —
   never ask). The summary must reflect the **final** state: the PR URL, that CI is
   green, and that review comments were addressed.
2. Report back in the session: PR URL, CI status, tracker status, what the watch loop
   changed after the first push, which comments you addressed, which gates were
   skipped because the repo does not define them, and anything deliberately left for a
   follow-up.

---

## Escalation — stop and ask ONLY for

- **Production writes, deploys, or destructive / irreversible actions.**
- **Customer-facing sends** — real outreach to real recipients.
- **An ambiguous gate or CI failure** — a check fails in a way you cannot confidently
  resolve: flaky vs. real, unclear error, environment gap, possibly pre-existing.
- **A non-converging watch loop** — CI still red after 3 fix attempts on the same job.
- **A product-judgment call with no source-of-truth answer** that Step A (in
  `build-loop.md`) did not close.
- **A missing credential or permission** you would have to work around.

For everything else — branching, planning, implementing, fixing your own gate
failures, and tracker writes on `TICKET` itself — **decide and proceed. Do not check
in.**
