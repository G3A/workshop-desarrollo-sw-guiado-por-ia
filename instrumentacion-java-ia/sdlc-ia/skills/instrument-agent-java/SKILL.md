---
name: instrument-agent-java
description: Install the non-deterministic instrumentation layer in a Java/Maven repository — project-scoped MCP servers in .mcp.json, plus a catalogue of Claude Code hooks in .claude/settings.json backed by portable shell scripts. The catalogue covers a secret read-guard, scoped Spotless formatting, a dangerous-command blocker, a dependency sweep, an audit log, and guards for centrally-managed dependency versions and Flyway/Liquibase migrations. Every hook is proven to fire before the run ends. Invoke with `/sdlc-ia:instrument-agent-java`.
disable-model-invocation: true
---

# instrument-agent-java — Give the Agent Tools, and Limits

You are installing the **non-deterministic instrumentation** layer: the sensors whose engine is
inference rather than computation. Its sibling, `instrument-project-java`, installs the
deterministic half — the controls that decide, in milliseconds and with no ambiguity, whether the
code is fine. This skill installs the half that applies **the team's judgement to the work that
actually requires judgement**: which systems the agent may reach, which files it may open, what
happens to a file the moment it is written.

Two artifacts, in this order:

| Order | Artifact | What it changes |
|---|---|---|
| 1 | `.mcp.json` | What the agent can **reach** — the team's tracker, docs and data, queried directly instead of pasted into chat |
| 2 | `.claude/settings.json` + `scripts/agent-hooks/*.sh` | What the agent **cannot get past** — checks that run whether or not it thought to run them |

MCP first, hooks second. MCP only adds capability; hooks take it away.

## The catalogue

Seven hooks. **1 and 2 are the default**; 3 to 7 are offered, and 6 and 7 only when the repository
actually contains the artifact they protect. `references/hook-catalog.md` carries the full
reasoning for each — read it before Phase 3.

| # | Hook | Event | Blocks | Default |
|---|---|---|---|---|
| 1 | Secret read-guard | `PreToolUse: Bash\|Read` | **yes** | on |
| 2 | Format on edit | `PostToolUse: Edit\|Write\|MultiEdit` | no | on |
| 3 | Dangerous-command blocker | `PreToolUse: Bash` | **yes** | offered |
| 4 | Dependency sweep | `SessionStart` | no — reports only | offered |
| 5 | Audit log | `PreToolUse` (async) | no | offered |
| 6 | Version-pin guard | `PostToolUse` | no — warns | offered **if** the pom already uses `<dependencyManagement>`/a BOM |
| 7 | Generated-file guard | `PreToolUse` | **yes** | offered **if** Flyway or Liquibase migrations exist |

## Philosophy (hold these throughout)

- **Resolve every version, then pin it.** Read the formatter, the pom path and the sweep command
  out of the repo — never copy them from this file. The same rule decides the MCP stdio packages:
  resolve with `npm view <package> version` when you write `.mcp.json`, then write that number
  down. A bare `npx -y <package>` re-resolves on every session, so a committed `.mcp.json` runs
  code tomorrow that nobody reviewed today.
- **Encode what the repo already does.** A hook that fires on a legitimate, everyday action is not
  a sensor — it is a bug with a policy attached. Hook 6 is the clearest case: without centralised
  dependency management, a literal `<version>` is the correct way to declare a dependency, so the
  hook must not exist there.
- **Discover before you write.** The formatter, the build file, the sweep target, the default
  branch, the tracker, the database — all of it is in the repository. Ask only what is genuinely
  not.
- **Merge, never clobber.** `settings.json` and `.mcp.json` routinely already hold work that is
  not yours.
- **Never touch `.claude/settings.local.json`, and never touch `permissions`.** You write exactly
  one key: `hooks`.
- **A hook nobody saw fire is not a hook.** Phase 5 triggers every installed hook on purpose and
  reverts. A guard with a broken regex exits 0 and looks exactly like a guard that found nothing.
- **A short blocklist beats a long one.** A list that generates false positives gets the hook
  switched off, taking the patterns that mattered with it.
- **Hooks are not a security boundary.** They run with the user's shell and permissions and they
  match text, not intent. Say this in the report.
- **Spell out every acronym the first time you speak to the user.** `PAT`, `DSN`, `BOM`, `CVE` are
  your vocabulary, not theirs. `MCP` is the exception — the product's name — and still gets one
  clause of explanation the first time.
- **Say what stays tied to Claude Code.** The scripts are plain shell and portable. The
  registration in `.claude/settings.json` is not: no other agent reads it today. State it plainly.
- **Everything you write is in English** — scripts, comments, JSON, hook messages, progress
  output — regardless of the conversation's language. The one exception is prose documentation
  that already exists: match the language of the file you are editing.
- **Never commit.** The only git writes are the break-and-restore of Phase 5, undone before the
  phase ends.

---

## Phase 1 — Discover (silent)

Run the full checklist in `references/discovery-checklist.md` (pom path, existing hooks/MCP
config, Makefile, formatter, hook preconditions, git facts, database, docs, team OS) and report it
as a table (artifact, status, what you found).

---

## Phase 2 — Prerequisites

Check what the hooks depend on; **install nothing yourself.**

| Tool | Check | Windows note |
|---|---|---|
| JDK + Maven (or the wrapper) | `./mvnw -v` | — |
| Git Bash | `bash --version` | **ships with Git for Windows.** Claude Code's shell-form hooks use it when present and fall back to PowerShell otherwise — and these scripts are not PowerShell |

`npx` is checked later, in Phase 3, only if a stdio MCP server is chosen — checking it earlier for
a decision nobody has made yet produces a warning nobody can act on.

---

## Phase 3 — Agree on scope

Ask **only what Phase 1 could not answer**, and never put an acronym in a question without
spelling it out. Use `AskUserQuestion` for the closed questions (max 4 options per call).

1. **Which MCP servers.** Offer only what Phase 1 derived — GitHub if there is a `github.com`
   remote or `.github/workflows/`, Context7 always, DBHub if a real connection string was found.
   Name the environment variable each one needs. Resolve `npx --version` and, for each stdio
   package chosen, `npm view <package> version` **in this step** — the number goes into Phase 4's
   file and into the report as `<package>@<resolved version>`.
2. **Which hooks, split into two multi-select questions** — never bundle two hooks into one
   option, never put caveats in an option's description (those belong in Phase 6):

   **"Which blocking hooks?"**

   | Option | Description |
   |---|---|
   | Secret guard *(recommended)* | Blocks reading `.env`, private keys, `secrets.json`/`.yml`. |
   | Dangerous commands | Blocks `rm -rf` outside the repo, `sudo`, force-push to `<branch>`, `mvn deploy`. |
   | Generated migrations *(hidden if no Flyway/Liquibase directory)* | Blocks editing an already-applied migration. |

   **"Which reporting hooks?"**

   | Option | Description |
   |---|---|
   | Format on edit *(recommended)* | Runs Spotless on the file you just edited. Measured here: `<N>s`. |
   | Dependency sweep | Lists outdated/vulnerable dependencies at session start. Measured here: `<N>s`. |
   | Audit log | Records every tool call to `logs/audit.log`. Not committed. |
   | Version-pin guard *(hidden if the pom has no `<dependencyManagement>`)* | Warns when a new dependency pins its own version instead of using the centralised one. |

   Pre-check the secret guard and format-on-edit. Measure the format command and the sweep command
   before offering them (Phase 1 already timed the format command; time the sweep command now).
3. **The audit log, if chosen.** It records full `tool_input` — confirm explicitly.
4. **Protected branches**, only if hook 3 was chosen and there is more than one long-lived branch.
5. **An existing hook on the same event**, only when Phase 1 found one. Default is to append.

---

## Phase 4 — Apply

**Confirm the working tree is clean first** (`git status`). If it is not, stop and say so — from
here the tree is dirty by design and Phase 5 can no longer tell your edits from the user's.

Ignore the agent's own footprint when counting:
`git status --porcelain | grep -vE '^\?\? (\.claude/|skills-lock\.json)' | wc -l` — read the
count, never `grep`'s exit code (1 on no matches is the clean case).

Write in this order:

1. **`.mcp.json`** — follow `references/mcp-servers.md`. Merge into `mcpServers`, never replace.
   Every credential is `${ENV_VAR}`; never a literal. If the team chose no servers, still write
   `{"mcpServers": {}}` when the file is absent.
2. **The hook scripts**, into `scripts/agent-hooks/`, starting with `_lib.sh` — the
   template→output mapping is in `references/hook-catalog.md`'s intro.

   Each template opens with an instruction header and its `{{PLACEHOLDER}}` list — resolve the
   placeholders from Phase 1 and **delete the header before writing the file**. **If hook 5 was
   chosen, add `logs/` to `.gitignore` before writing `audit-log.sh`** — reversed, the user's next
   `git add -A` publishes it. Then:

   ```bash
   chmod +x scripts/agent-hooks/*.sh
   for f in scripts/agent-hooks/*.sh; do bash -n "$f" || echo "SYNTAX ERROR: $f"; done
   ```
3. **`.claude/settings.json`** — `templates/settings.json.template`, carrying only the handlers
   chosen. Merge: keep every key and event you did not add, **append** your matcher group to an
   existing event's array. Confirm it still parses:
   `python3 -c "import json;json.load(open('.claude/settings.json'));print('ok')"`.

Report every resolved version alongside the file. Never write a secret in either file. After
writing, run each script once against a synthetic payload and confirm it exits 0 on a benign case.

---

## Phase 5 — Verify by breaking

**Mandatory.** Installing a hook proves nothing; making it fire does. Hook changes take effect
immediately — Claude Code's file watcher picks up `settings.json` edits without a restart — so
what Phase 4 wrote is live in this session.

For each installed hook: snapshot anything pre-existing you are about to edit, trigger it
**through the real tool call** per `references/verification-steps.md` §1, confirm the outcome and
that the message names the problem, restore from the snapshot (not `git checkout` — most of what
you touch is untracked). After every trigger, `git status` must look exactly as it did before it.

**Do not report success with a hook that did not fire.** Fix it, or remove it and say so.

MCP cannot be verified the same way — a freshly written `.mcp.json` leaves its servers at
`⏸ Pending approval` until the user trusts the workspace. Confirm the file parses, start each
stdio server once with `< /dev/null` to catch a dead flag early, and say plainly that this half of
the run was written, not proven, per `references/mcp-servers.md`.

---

## Phase 6 — Document and report

Update `AGENTS.md`, `README.md`, and (if present) `docs/infrastructure.md`/`docs/java.md` per
`references/report-and-docs.md` — what each doc needs, and when to skip it.

Report, in order: files created/modified (config and docs separately); the audit log's contents
and gitignore status, if installed; hooks **not** installed and why; MCP servers as
written-pending-approval with their resolved versions; known false positives, hook by hook
(leading with `cp .env.example .env` being denied); that `.claude/settings.json` is Claude Code's
alone — no other agent reads it.

**Then, last,** walk the **Try it** table in `references/verification-steps.md` §2 with the user —
one line per installed hook and registered server.

Do not commit. Leave the changes for the user to review.

---

## Reference

| Reference | Used by | What it covers |
|---|---|---|
| `references/discovery-checklist.md` | Phase 1 | The 10-item discovery checklist |
| `references/hook-catalog.md` + `references/hook-catalog-2.md` | Phase 3, Phase 4, Phase 5 | The seven hooks: what each does, why, what it costs, what it misses |
| `references/mcp-servers.md` | Phase 1, Phase 3, Phase 4 | Deriving the server menu from the repo, and the config shape of each |
| `references/verification-steps.md` | Phase 5, Phase 6 | Per-hook trigger/expected table, and the "Try it" walkthrough |
| `references/report-and-docs.md` | Phase 6 | Which doc gets what, and when to skip one |
| `references/troubleshooting.md` | Whenever something doesn't fire as expected | Symptom → likely cause → how to confirm |
| `templates/` | Phase 4 | The file skeletons |

## Rules

- Do NOT write to `.claude/settings.local.json`, and do NOT touch the `permissions` key anywhere.
- Do NOT replace an existing `hooks` or `mcpServers` block. Append.
- Do NOT write a credential into `.mcp.json`. Use `${ENV_VAR}`.
- Do NOT install a hook whose precondition the repository does not meet.
- Do NOT hardcode a package version, a pom path, a formatter command, or a branch name. Read them.
- Do NOT report success until every installed hook has been seen to fire and every trigger has
  been reverted.
- Do NOT commit or push.
- DO write `logs/` into `.gitignore` before the audit log exists, not after.
- DO say which hooks you did not install, and why.
- DO say, in the final report, that the hooks run only under Claude Code.
