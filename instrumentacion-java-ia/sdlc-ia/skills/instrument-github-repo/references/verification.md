# Verify by blocking

The rest of the plugin proves a gate by **breaking** something and watching the gate stop it. This
skill cannot break a file — its effect lives on GitHub — so it proves the gate by **attempting a
merge that must fail**.

A `201` from the API means the JSON was accepted. It says nothing about whether anything is
protected. The three ways a ruleset is accepted and still protects nothing:

- `conditions.ref_name.include` holds a bare branch name instead of a full ref → matches no branch.
- `enforcement` is `"evaluate"` → logs, never blocks.
- The required `context` never appears on a run → blocks everything, which is a different bug and
  looks the same from the outside for the first hour.

Only an attempted merge tells them apart.

---

## Procedure

### 1. A throwaway branch and PR

```
git switch --create chore/verificacion-ruleset origin/<protected-branch>
git commit --allow-empty -m "chore: verificar el ruleset"
git push --set-upstream origin chore/verificacion-ruleset
gh pr create --base <protected-branch> --title "chore: verificar el ruleset" --body "PR desechable. Se cierra sola."
```

An empty commit is enough: the point is the merge attempt, not the diff.

### 2. Read what GitHub says about mergeability

```
gh pr view <n> --json mergeable,mergeStateStatus,statusCheckRollup
```

**Expect `mergeStateStatus` to be `BLOCKED`.** Anything else means the ruleset is not reaching this
branch — go back to `conditions.ref_name` and check for a bare branch name.

Do not read this from the web UI's grey button alone: the button also greys out for reasons that
have nothing to do with the ruleset.

### 3. Confirm the reason is the one you configured

The rollup has to name the required context you wrote, and it has to be **pending or successful, not
absent**. An absent context is the failure mode from Phase 1: the ruleset requires something no run
ever produces, so the PR is blocked forever for the wrong reason.

If the ruleset requires an approving review, confirm that too is listed as missing — with the count
you agreed on, not a different one.

### 4. Confirm it lets through what it should

The gate that blocks everything is as broken as the gate that blocks nothing, and it is the one
people discover later, after it has cost a day. Wait for the check to finish on the throwaway PR and
confirm the block reason narrows to the review requirement alone.

On a repo with an Administrator bypass, confirm the bypass behaves: the merge button is available to
an admin and the reason is stated in the report, so nobody mistakes "I could merge it" for "the gate
is not installed".

### 5. Clean up

```
gh pr close <n> --delete-branch
```

Never merge the throwaway PR, and **never delete or relax the ruleset to make this step pass**. If
the block cannot be demonstrated, the ruleset is wrong — report it as not working rather than
removing the evidence.

---

## Closing

```
gh api repos/{owner}/{repo}/rulesets --jq .[].name
```

The rulesets you wrote, and nothing else added or removed.

**Do not report success without the Phase 2 output showing `BLOCKED`.** "Created the ruleset" and
"the gate works" are different claims, and only the second one is what this skill exists for.
