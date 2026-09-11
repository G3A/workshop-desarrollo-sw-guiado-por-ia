# Ruleset anatomy

Every shape here was **read from a live repository** (`gh api repos/{owner}/{repo}/rulesets/{id}`),
not from documentation. When the API changes, re-read a real one rather than trusting this file.

## The envelope

```json
{
  "name": "integration-dev",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/heads/dev"], "exclude": [] } },
  "bypass_actors": [],
  "rules": []
}
```

- `target` is `"branch"`. Tag and push rulesets exist and are out of scope here.
- `conditions.ref_name.include` takes **full refs** (`refs/heads/dev`), not bare branch names. A
  bare name silently matches nothing, and the ruleset looks installed while protecting no branch —
  the single most likely way to ship a fake gate from this skill.
- `enforcement` is `"active"` (blocks) or `"evaluate"` (logs only). `evaluate` is a legitimate
  choice for a team easing in; it is not a gate, and the report must say so.

## The four rules

### `deletion` and `non_fast_forward`

```json
{ "type": "deletion" }
{ "type": "non_fast_forward" }
```

No parameters. The first stops the branch from being deleted, the second stops force-pushes. Cheap,
uncontroversial, and they close the two ways to get around every other rule by removing the branch
or rewriting it.

### `pull_request`

```json
{
  "type": "pull_request",
  "parameters": {
    "allowed_merge_methods": ["squash"],
    "required_approving_review_count": 1,
    "dismiss_stale_reviews_on_push": true,
    "required_review_thread_resolution": true,
    "require_code_owner_review": false,
    "require_last_push_approval": false,
    "require_extra_approval_for_unattributed_changes": true,
    "required_reviewers": []
  }
}
```

- `allowed_merge_methods` is what enforces the history shape. See the asymmetry below.
- `dismiss_stale_reviews_on_push: true` — an approval refers to the diff that was reviewed. Without
  this, a push after approval ships unreviewed code with a green approval on it.
- `required_review_thread_resolution: true` — an unresolved comment is an open question; merging
  over it is how review feedback quietly gets lost.
- `require_code_owner_review` needs a `CODEOWNERS` file. Leave it `false` unless one exists —
  requiring an owner who is not defined blocks everything.

### `required_status_checks`

```json
{
  "type": "required_status_checks",
  "parameters": {
    "required_status_checks": [{ "context": "check" }],
    "strict_required_status_checks_policy": true,
    "do_not_enforce_on_create": false
  }
}
```

**`context` is the job name as it appears on a completed run** — not the workflow file name, not the
workflow's `name:`. Read it from reality:

```
gh api repos/{owner}/{repo}/commits/{sha}/check-runs --jq .check_runs[].name
```

A context that never appears leaves every PR blocked forever, waiting for a check that does not
exist. It looks exactly like a working gate until someone waits an hour, which is why Phase 1 of the
skill refuses to proceed without one completed run.

## The asymmetry between the two branches

The observed rulesets differ in exactly two fields, and both differences are deliberate:

| | Integration branch | Release branch |
|---|---|---|
| `allowed_merge_methods` | `["squash"]` | `["merge"]` |
| `strict_required_status_checks_policy` | `true` | **`false`** |

**Merge method.** The integration branch squashes so its history reads one commit per PR. The
release branch uses a merge commit so it *preserves* those commits instead of squashing an
already-squashed history into a single opaque entry.

**The strict policy is the one that looks like a bug and is not.** `strict` means "the branch must
be up to date with its base before merging". On a release branch that is fatal: because integration
merges by squash, the integration branch never contains the merge commits of previous releases, so
it is permanently "behind" the release branch. With `strict: true` there, **every release is
blocked forever** as out of date.

Copying the same template to both branches is the failure this table exists to prevent.

## Bypass actors

```json
{ "bypass_actors": [{ "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" }] }
```

`actor_id: 5` with `actor_type: "RepositoryRole"` is the **Admin** role.

A bypass is a hole in the gate. There is one honest reason to open it: **a repository with a single
person cannot satisfy `required_approving_review_count: 1`**, because nobody can approve their own
pull request. The exit is either `0` approvals or `1` plus this bypass — and if it is the bypass, it
is a temporary exception that gets removed when a second person joins.

Write it only after saying that out loud, and record the removal condition in `AGENTS.md`. A bypass
nobody remembers agreeing to is a gate that stopped being one.
