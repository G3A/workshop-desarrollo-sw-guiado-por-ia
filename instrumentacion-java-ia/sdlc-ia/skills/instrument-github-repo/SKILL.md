---
name: instrument-github-repo
description: Turn the CI workflow into an actual gate by writing the GitHub Ruleset that requires it — a status check in green, an approving review, and the merge method the branch's role calls for. Reads the repository's real branch topology first, never overwrites an existing ruleset (it reports the differences instead), and proves the gate by confirming a merge is genuinely blocked rather than trusting a 201 from the API. Invoke with `/sdlc-ia:instrument-github-repo`.
disable-model-invocation: true
---

# instrument-github-repo — Make the Checks Actually Block

**A workflow that runs blocks nothing.** `instrument-project-java` installs thirteen controls and
writes `.github/workflows/ci.yml`; what stops a merge is the **Ruleset** that requires that
workflow. Without it a repository has every sensor and no judge — the checks report, and the merge
goes through anyway.

This skill writes that Ruleset. It is the other half of control 8, and it lives in its own skill
because it changes **repository settings through the GitHub API**, not files in the working tree:
different permissions, different failure modes, different blast radius.

| Artifact | What it changes |
|---|---|
| Ruleset on the integration branch | No merge without the check green and a review |
| Ruleset on the release branch (only if the repo has one) | Same, with the merge method releases need |

## Philosophy

- **Never overwrite an existing ruleset.** It may carry protections someone added by hand.
  Report the differences and let a person decide — the same "merge, never clobber" rule the rest of
  the plugin follows with `Makefile`, `.mcp.json` and `settings.json`.
- **Read the topology, do not assume it.** Which branch integrates, which releases, whether a
  release branch exists at all — from the repo and its `AGENTS.md`, never from a default.
- **A ruleset nobody saw block is not a gate.** Verify by attempting a merge that must fail. A
  `201` from the API proves the call succeeded, not that the rule works.
- **Explain every bypass.** A bypass actor is a hole in the gate. It can be the right call, and it
  is never a field to write silently.
- **Ask what the team's size decides.** Approval count and merge method depend on how many people
  work here. Discovery cannot answer that; ask, with the consequence stated.
- **Never delete a ruleset.** Not on conflict, not on error, not on cleanup.

---

## Phase 1 — Discover (silent)

```
gh auth status
gh repo view --json nameWithOwner,defaultBranchRef,visibility,isPrivate
gh api repos/{owner}/{repo}/rulesets --jq .[].name
gh api repos/{owner}/{repo}/branches --jq .[].name
gh api repos/{owner}/{repo}/actions/workflows --jq .workflows[].name
```

Establish, and report as a table:

1. **Branch topology.** Which branch is the integration target and which is the release target —
   read `AGENTS.md`/`CLAUDE.md` first, fall back to the remote default branch. A repo with a single
   branch gets one ruleset, not two.
2. **Existing rulesets**, by name and target. If one already covers a branch you were going to
   protect, you are in **report mode** for that branch (Phase 4).
3. **The status check's real context name.** This is the field people get wrong: the required
   context is the **job name** as it appears on a completed run, not the workflow file name and not
   the workflow's `name:`. Read it from an actual run:
   `gh api repos/{owner}/{repo}/commits/{sha}/check-runs --jq .check_runs[].name`.
   **If the workflow has never run, stop and say so** — a ruleset requiring a context that never
   appears blocks every merge forever, and looks like a broken repository rather than a
   misconfiguration.
4. **Team size signal.** `gh api repos/{owner}/{repo}/collaborators --jq length`. It does not decide
   anything; it tells you which default to *suggest* in Phase 3.

---

## Phase 2 — Prerequisites

| Check | Why |
|---|---|
| `gh auth status` | Every call needs it |
| Admin on the repo | Rulesets are an admin-only endpoint; without it the call fails with 403 and nothing else in this skill works |
| At least one completed run of the target workflow | Phase 1 item 3 — the context name has to exist |

Report what is missing and stop. Do not attempt a partial install.

---

## Phase 3 — Agree on scope

Ask only what discovery could not resolve. State the consequence of each answer, not just the
options.

1. **Which branches to protect** — the integration branch always; the release branch only if the
   topology has one.
2. **Approving reviews.** `1` is the right answer for a team. **For a single-person repository it
   blocks everything**: nobody can approve their own PR. Two honest exits — `0` approvals, or `1`
   plus an Administrator bypass (question 4). Say which one the repo is heading for.
3. **Merge method per branch.** The integration branch usually wants `squash` (one commit per PR,
   so the history reads PR by PR); a release branch usually wants `merge`, to preserve the commits
   the integration branch already squashed. If the repo's `AGENTS.md` states a convention, follow
   it and say so instead of asking.
4. **Bypass actors.** Default none. Offer the Administrator role **only** when question 2 left a
   single-person repo blocked, and say out loud that it is a temporary exception to be removed when
   a second person joins — never present it as ordinary configuration.
5. **Enforcement.** `active` by default. Offer `evaluate` (log, do not block) for a team that wants
   to watch it for a sprint first — a legitimate choice, but say that nothing blocks until it
   becomes `active`, so it is not mistaken for a working gate.

Read `references/ruleset-anatomy.md` before writing anything: it carries the real, observed shape
of each rule and the asymmetry between the two branches.

---

## Phase 4 — Apply

**Confirm intent before the first write.** This is the only skill in the plugin whose effects live
outside the working tree — there is no `git checkout` to undo them.

Write from `templates/ruleset-integration.json.template` and, if applicable,
`templates/ruleset-release.json.template`:

```
gh api repos/{owner}/{repo}/rulesets --method POST --input ruleset-integration.json
```

**If a ruleset already covers that branch: do not write.** Fetch it
(`gh api repos/{owner}/{repo}/rulesets/{id}`), compare field by field against what you would have
written, and report a three-column table — *what it has* / *what this skill would write* / *what
that changes*. Then stop and let a person decide. Updating is `--method PUT` on the existing id and
is **a person's call, never this skill's**.

---

## Phase 5 — Verify by blocking

A `201` means the API accepted the JSON. It does not mean the gate works. Follow
`references/verification.md`. In short:

1. Open a throwaway PR against the protected branch.
2. Confirm GitHub reports the merge as blocked, and **why** — required check pending or missing
   review, named.
3. Confirm the required context matches a check that actually reports. A context that never
   arrives leaves the PR blocked forever, which looks identical to a working gate until someone
   waits an hour.
4. Close the PR and delete the branch.

**Do not report success on a ruleset you did not see block something.**

---

## Phase 6 — Document and report

Update `AGENTS.md` if it exists: which branches are protected, what each ruleset requires, and
every bypass with its reason and its removal condition.

Report: each ruleset created (name, target branch, rules) or reported-not-written with its
difference table; the required context name and the run it was read from; the evidence from Phase 5
that a merge was blocked; every bypass actor and why it exists; and what stays manual —
`CODEOWNERS`, deployment Environments and organization-level rulesets are outside this skill.

---

## Reference

- `references/ruleset-anatomy.md` — every rule's observed shape, and why the two branches differ.
- `references/verification.md` — the block-and-restore procedure.
- `templates/` — the two ruleset bodies.

## Rules

- Do NOT overwrite or update an existing ruleset. Report the differences and stop.
- Do NOT delete a ruleset, for any reason.
- Do NOT write a ruleset requiring a context that has never appeared on a completed run.
- Do NOT add a bypass actor without stating why it exists and when it gets removed.
- Do NOT report success without having seen a merge blocked.
- Do NOT touch organization-level rulesets, `CODEOWNERS`, or deployment Environments — name them
  as what remains manual.
- DO read the branch topology from the repo and its `AGENTS.md`, never from a default.
