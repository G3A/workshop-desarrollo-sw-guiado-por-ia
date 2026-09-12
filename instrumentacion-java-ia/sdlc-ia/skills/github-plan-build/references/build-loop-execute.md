# The build loop — implement to merged-ready (Steps F–K)

> Continues `references/build-loop.md` (Steps A–E: grill, explore, plan, adversarial review,
> approval checkpoint) for the `github-plan-build` skill. Same bindings, same tracker-agnostic
> scope. Only Step E (in the other file) pauses for the user — everything here is **act and
> self-verify**, except the optional `confirm-push` pause in Step H; see **Escalation** below
> for the complete list of things that stop you.

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

   **Then commit that step — one commit per green step.** Stage only the step's files;
   the message says what the step did, references the ticket with `Refs #<n>` (the
   child's number instead, when `CHILD-LINK` applies), and ends with the
   `Asistido-por-IA` trailer described in Step H. The commit that completes the **last**
   step of the plan carries `LINK-TOKEN` (`Closes #<n>`) instead of `Refs` — the task
   list tells you which one that is, so decide it before writing the message; never
   amend a commit afterwards to add it. A history that reads task by task is what lets
   a reviewer follow the plan in the diff and revert one step without losing the rest.

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
2. Step F already committed each step. What is left for this commit is whatever Step G
   changed — a gate fix, a `/code-review` finding — as one more `Refs #<n>` commit; if
   Step G changed nothing, there is nothing to commit here and that is fine. Every
   commit on `BRANCH` references `TICKET` (`Refs #<n>`), and exactly one — the one that
   completed the last step in Step F — carries `LINK-TOKEN` so the tracker attaches the
   branch to `TICKET`. **If `TICKET` has sub-issues**, the binding table's `CHILD-LINK`
   step (where defined) says how a child's commits reference the child instead of the
   parent — but does **not** close it here. A child's own code is not proven until
   Step I says the PR is green with comments addressed; closing on the commit alone
   would mark a sub-issue done while CI can still fail it or a reviewer can still ask
   for changes to it. `LINK-TOKEN` on `TICKET` itself is still reserved for the commit
   that finishes the **last** step of the last remaining child, or of a ticket with no
   children at all.

   **Every commit also ends with the trailer `Asistido-por-IA: <model id>`** — the model
   that is running this session, as the harness reports it (e.g. `claude-opus-5`), never
   guessed. Git trailer format: last paragraph of the message, `Key: value`, separated
   from the `LINK-TOKEN` line by a blank line, since `Closes #<n>` is not a trailer and
   would break the block. Any `Co-Authored-By` or session trailers the harness adds go in
   the same paragraph.

   **Read it back with `--grep`, anchored — never with git's trailer reader.** The obvious
   command is `--format` with `%(trailers:key=Asistido-por-IA,valueonly)`, and on a
   squash-merged history it **returns empty for every commit**. This is not a formatting
   mistake you can avoid: **GitHub rewrites the message when it squashes**, separating each
   trailer with a blank line and moving `Co-authored-by` last, so the final contiguous block
   — the only one git parses — is that one line. Verified over 109 first-parent commits of
   this monorepo: `0` with the trailer reader, `23` with `--grep`.

   ```
   git log --first-parent --grep="^Asistido-por-IA: " --format="%h %s"
   ```

   **Anchor the pattern.** A bare `--grep=Asistido-por-IA` also matches a commit that merely
   *mentions* the marker in prose — a commit documenting this very behaviour counts as
   AI-assisted. `^Asistido-por-IA: ` matches the trailer line and nothing else.

   **`confirm-push` checkpoint — only when the argument was given.** With every commit
   in place and Step G green, stop *before* the push and ask once with
   `AskUserQuestion`. The question shows what would leave the machine: `BRANCH`, the
   commits (`git log --oneline <base>..HEAD`), the files changed, and the PR title.
   Two options:
   - **Push and open the PR (Recommended)** — continue with H.3.
   - **Stop here, I push it myself** — end the run *without* pushing. Steps H.3–J do not
     run: no push, no PR, no `COMMENT`, no `STATUS→IN-REVIEW`. Report the branch name,
     the exact `git push -u origin <BRANCH>` and `OPEN-PR` commands the developer will
     run, and that the issue was left in-progress on purpose, so the wrap-up is not
     mistaken for forgotten.
   Without `confirm-push` there is no question here: pushing is pre-authorized by the
   **Autonomy contract** in `SKILL.md`.
3. Push `BRANCH`.
4. Open the PR with `OPEN-PR`. The body links `TICKET`, summarizes the change, lists
   **the verification commands you actually ran** with their results — not the ones you
   intended to run — and repeats the `Asistido-por-IA` trailer as its last line, so a
   squash merge that takes the PR body as the commit message keeps it.

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
   green, and that review comments were addressed. If the PR targets a branch that is
   not the repository's default one (the calling skill's `BASE-BRANCH`, where defined),
   say in the comment and in the session that `LINK-TOKEN` will **not** auto-close
   `TICKET` when this PR merges — it closes when the integration branch is released to
   the default branch, and whoever needs it closed earlier closes it by hand.
2. Report back in the session: PR URL, CI status, tracker status, what the watch loop
   changed after the first push, which comments you addressed, which gates were
   skipped because the repo does not define them, and anything deliberately left for a
   follow-up.

## Step K — Route the lesson

The round is closed. This is the only step that makes round 20 different from round 1: without it
every round learns what the last one already learned.

**You propose. You do not apply.** Nothing here edits `AGENTS.md`, the review checklist, an ADR or
a sensor. Two reasons, and the second one is mechanical:

- A step that edits the file governing the agent closes a loop where the agent writes its own
  rules and the next round reads them, with nobody looking in between.
- **The PR is already open and green from Step J.** A rule change is about the *process*, not
  about this feature: it changes every round that follows, so it gets reviewed differently and
  belongs in its own PR — not appended to one that has already been reviewed.

1. **Find the candidates.** Three questions about the round that just closed — not about history:
   - What cost more than the plan expected, and why?
   - What did you have to say by hand that the agent should have known? **If it is the third time
     you say it, that is not a discipline problem — it is a missing rule.**
   - What did a gate catch late, or what did no gate catch at all?

   **No candidates is a valid outcome.** Say so and stop. A round that taught nothing is common,
   and inventing a lesson to fill the step poisons the files it would be written into.

2. **Route each one** by *what it became*, not by how important it feels. Exactly one destination
   each:

   | If the lesson is… | It goes to… | Shaped as |
   |---|---|---|
   | A rule the agent must respect while generating | `AGENTS.md` | One short line |
   | A criterion that applies only while reviewing | the review checklist (`REVIEW.md`, where the repo has one) | One bullet: what to look for in the diff |
   | The reasoning behind a decision already discarded | an ADR under `docs/adrs/` | Context, decision, consequences |
   | Something a machine can check | it stops being text | a test, a lint rule, or a hook |
   | A preference of yours, not the team's | `~/.claude/CLAUDE.md` | outside the repo, deliberately |
   | Not yet any of these | `docs/lecciones.md` — the waiting room | One line, **with the date it entered** |

   **The fourth row is the one that pays and the one least used.** A written criterion is
   forgotten; a sensor is not. Before proposing a line of prose, ask whether the same lesson could
   be a failing test instead.

3. **Write the exact text, not a description of it.** "Add something about null handling to
   `AGENTS.md`" is not a proposal — it leaves the user to do the thinking again. Give the line as
   it would be pasted, and name the file and the section it goes under.

4. **Report and stop.** One table — lesson, destination, the text, and whether you recommend
   applying it now or waiting for a second sighting. The user applies what they agree with.

### The waiting room, and why the date is the mechanism

`docs/lecciones.md` holds what is not yet a rule: a single observation, seen once, that would be
premature as a rule and lost as nothing.

**Every entry is meant to leave** — promoted to one of the five destinations above, or deleted.
The entry date is what makes that visible: an entry sitting there for months is not a pending
lesson, it is a lesson that was not one. Without the date the file becomes exactly the dumping
ground the method says it must not be.

**Do not propose injecting this file into the agent's context automatically.** The waiting room is
supposed to be uncomfortable. Convenient retrieval is what kills the pressure to promote — the
same argument by which this package carries no memory server.

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
in.** The one opt-in exception is the `confirm-push` pause in Step H, and only when
the user asked for it in the arguments.
