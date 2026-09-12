---
name: instrument-agent-java
description: Install the agent-facing instrumentation layer in a Java/Maven repository — project-scoped MCP servers in .mcp.json (non-deterministic: the model decides when to call them), including a browser server when the repository has a user interface, plus a catalogue of deterministic Claude Code command hooks in .claude/settings.json backed by portable shell scripts. The catalogue covers a secret read-guard, scoped Spotless formatting, a dangerous-command blocker, a dependency sweep, an audit log, an MCP write guard, and guards for centrally-managed dependency versions and Flyway/Liquibase migrations. Every hook is proven to fire before the run ends. Invoke with `/sdlc-ia:instrument-agent-java`.
disable-model-invocation: true
---

# instrument-agent-java — Give the Agent Tools, and Limits

You are installing the **agent-facing instrumentation** layer: which systems the agent may reach,
which files it may open, what happens to a file the moment it is written. Its sibling,
`instrument-project-java`, installs the code-facing half — the sensors that run on the repository
itself (build, style, architecture, CI) whether or not an agent is open.

The two artifacts you install sit on **opposite sides of the instrumentation axis**. A control is
deterministic only when both the trigger and the decision stay outside the model's reasoning:

- **MCP servers are non-deterministic.** The model decides when to call a tool, and with which
  arguments. They add capability; they do not constrain it.
- **The nine `type: command` hooks are deterministic.** The agent's lifecycle fires them at a
  fixed point (`PreToolUse`, `PostToolUse`, `SessionStart`…) and a shell script — not the model —
  decides allow, block or report. This is why they are a limit and MCP is not.

Do not describe this skill as "the non-deterministic layer": it installs one half of each.

Two artifacts, in this order:

| Order | Artifact | What it changes |
|---|---|---|
| 1 | `.mcp.json` | What the agent can **reach** — the team's tracker, docs and data, queried directly instead of pasted into chat |
| 2 | `.claude/settings.json` + `scripts/agent-hooks/*.sh` | What the agent **cannot get past** — checks that run whether or not it thought to run them |

MCP first, hooks second. MCP only adds capability; hooks take it away.

## The catalogue

Nine hooks. **1 and 2 are the default**; 3 to 9 are offered, and 6, 7, 8 and 9 only when the
repository (or the team's own environment) actually meets their precondition.
`references/hook-catalog.md` carries the full reasoning for each — read it before Phase 3.

| # | Hook | Event | Blocks | Default |
|---|---|---|---|---|
| 1 | Secret read-guard | `PreToolUse: Bash\|PowerShell\|Read` | **yes** | on |
| 2 | Format on edit | `PostToolUse: Edit\|Write\|MultiEdit` | no | on |
| 3 | Dangerous-command blocker (Bash) | `PreToolUse: Bash` | **yes** | offered |
| 4 | Dependency sweep | `SessionStart` | no — reports only | offered |
| 5 | Audit log | `PreToolUse` (async) | no | offered |
| 6 | Version-pin guard | `PostToolUse` | no — warns | offered **if** the pom already uses `<dependencyManagement>`/a BOM |
| 7 | Generated-file guard | `PreToolUse` | **yes** | offered **if** Flyway or Liquibase migrations exist |
| 8 | Dangerous-command blocker (PowerShell) | `PreToolUse: PowerShell` | **yes** | offered **if** the team is on Windows (discovery item 10) |
| 9 | MCP write guard | `PreToolUse: mcp__*` | **yes** | offered **if** at least one MCP server is registered |

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
- **Never touch `.claude/settings.local.json`** — it is the user's, not yours.
- **`permissions` is written only with explicit confirmation, and only for `mcp__*` matchers.**
  This rule used to be an absolute "never". It was wrong, and the skill contradicted itself on it:
  `references/hook-catalog.md` already tells the user to close a gap with "a `Read` deny rule in
  permissions, not a hook" while refusing to write one. Worse, the skill's own order —
  **"MCP first, hooks second, because MCP only adds capability and hooks take it away"** — was
  half-executed: it registered the servers that widen what the agent can reach and then declined to
  configure the one deterministic mechanism that narrows them. Phase 4 fixes that. Everything
  outside `mcp__*` still belongs to the user and is never touched.
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
config, Makefile, formatter, hook preconditions, git facts, database, **whether the repo has a user
interface**, docs, team OS) and report it as a table (artifact, status, what you found).

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
   remote or `.github/workflows/`, Context7 always, DBHub if a real connection string was found,
   and **a browser server only if checklist item 8b found a user interface**. Name the environment
   variable each one needs. Resolve `npx --version` and, for each stdio package chosen,
   `npm view <package> version` **in this step** — the number goes into Phase 4's file and into the
   report as `<package>@<resolved version>`.

1b. **Which browser server** — only when the previous question included one. **Ask; do not
    default.** The two answer different questions, and which one the team needs is not something
    the repository can tell you:

    | Option | Description |
    |---|---|
    | Playwright MCP | "Does it work, and does it look right?" Reads the accessibility tree, not screenshots — a visual regression needs an explicit screenshot request. Same repository and licence as Playwright. |
    | Chrome DevTools MCP | "Why is it slow, and why did that request fail?" Performance traces, network requests, console messages with source-mapped stack traces. From the Chrome DevTools team. |

    Neither needs a browser installed first, and **neither is a reason to add Playwright to the
    repository's dependencies** — choosing a testing stack is not this skill's call.

    **Say in the same breath what registering it does not buy.** Playwright MCP's own documentation
    calls `--allowed-origins` and the file-access guardrail *convenience defenses, not a security
    boundary*, and says real isolation needs client-level permissions. That is this skill's axis,
    from the vendor: the `mcp__*` rules of scope question 8 are what actually narrows it. And a
    browser server pulls page content into the model's context, which is the classic
    prompt-injection surface — `references/mcp-servers.md` carries both caveats in full.
2. **Which hooks, split into two multi-select questions** — never bundle two hooks into one
   option, never put caveats in an option's description (those belong in Phase 6):

   **"Which blocking hooks?"**

   | Option | Description |
   |---|---|
   | Secret guard *(recommended)* | Blocks reading `.env`, private keys, `secrets.json`/`.yml`. |
   | Dangerous commands (Bash) | Blocks `rm -rf` outside the repo, `sudo`, force-push to `<branch>`, `mvn deploy`. |
   | Dangerous commands (PowerShell) *(hidden unless the team is on Windows — discovery item 10)* | The same four categories, `Remove-Item`/`runas`/force-push/`mvn deploy`, for PowerShell's own syntax — a separate script, not this one with a wider matcher. |
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
4. **Protected branches**, only if hook 3 and/or hook 8 was chosen and there is more than one
   long-lived branch — both scripts take the same `PROTECTED_BRANCHES` list, resolved once.
5. **An existing hook on the same event**, only when Phase 1 found one. Default is to append.
6. **Permission rules over the MCP tools**, only if at least one server is being registered. This
   is the **one question in the skill that asks to write outside the `hooks` key**, so it is asked
   plainly and never assumed:

   > Registering these servers widens what the agent can reach on its own. The only deterministic
   > way to narrow it again is a `permissions` block in `.claude/settings.json`. That file is
   > yours, and this skill does not touch it unless you say so here.
   >
   > `Yes — deny writes, allow reads` · `Yes — but show me the rules first` · `Skip`

   Say what "writes" means for the servers actually being registered, with two or three real tool
   names from their own lists, so the answer is about this repository and not about the idea. If
   the answer is `Skip`, the report states that the servers were registered **without** a
   deterministic limit, in those words — silence here reads as "it is handled".

---

## Phase 4 — Apply

**Confirm the working tree is clean first** (`git status`). If it is not, stop and say so — from
here the tree is dirty by design and Phase 5 can no longer tell your edits from the user's.

Ignore the agent's own footprint when counting: run `git status --porcelain`, drop the lines
matching `^\?\? (\.claude/|skills-lock\.json)`, and count what is left — zero is clean. In
PowerShell: `(git status --porcelain | Where-Object { $_ -notmatch '^\?\? (\.claude/|skills-lock\.json)' }).Count`;
in bash: `git status --porcelain | grep -vE '^\?\? (\.claude/|skills-lock\.json)' | wc -l` (read
the count, never `grep`'s exit code — 1 on no matches is the clean case).

Write in this order:

1. **`.mcp.json`** — follow `references/mcp-servers.md`. Merge into `mcpServers`, never replace.
   Every credential is `${ENV_VAR}`; never a literal. If the team chose no servers, still write
   `{"mcpServers": {}}` when the file is absent.

   **Then read each server's tool list and report the contradictions**, in the same pass that
   confirms the server starts — `references/mcp-servers.md`, "Read what each server declares".
   You are already connected, and a contradiction found here is found **before** the server is
   committed. Report contradictions, never the annotations themselves: repeating what a server says
   about itself is what the specification warns against. **"Could not read them" is a finding**, not
   a clean result.
2. **The hook scripts**, into `scripts/agent-hooks/`, starting with `_lib.sh` — the
   template→output mapping is in `references/hook-catalog.md`'s intro.

   Each template opens with an instruction header and its `{{PLACEHOLDER}}` list — resolve the
   placeholders from Phase 1 and **delete the header before writing the file**. **If hook 5 was
   chosen, add `logs/` to `.gitignore` before writing `audit-log.sh`** — reversed, the user's next
   `git add -A` publishes it. Then:

   Then syntax-check every script with `bash -n` (the hooks are bash by design; on Windows the
   `bash` is Git's). PowerShell:
   `Get-ChildItem scripts/agent-hooks/*.sh | ForEach-Object { bash -n $_.FullName; if (-not $?) { "SYNTAX ERROR: $($_.Name)" } }`;
   bash: `for f in scripts/agent-hooks/*.sh; do bash -n "$f" || echo "SYNTAX ERROR: $f"; done`,
   preceded by `chmod +x scripts/agent-hooks/*.sh` (no-op on Windows).
3. **`.claude/settings.json`** — `templates/settings.json.template`, carrying only the handlers
   chosen. Merge: keep every key and event you did not add, **append** your matcher group to an
   existing event's array. Confirm it still parses — PowerShell:
   `Get-Content -Raw .claude/settings.json | ConvertFrom-Json | Out-Null; 'ok'`; bash:
   `python3 -c "import json;json.load(open('.claude/settings.json'));print('ok')"`.

Report every resolved version alongside the file. Never write a secret in either file. After
writing, run each script once against a synthetic payload and confirm it exits 0 on a benign case.

### Narrowing what MCP widened

Only after the user confirmed it in scope question 8. Registering MCP servers widens what the agent
can reach; this is the half that narrows it, and without it the skill's own ordering rule —
"MCP first, hooks second, because MCP only adds capability and hooks take it away" — stops halfway.

**Two mechanisms, each where it earns its place.** The same division the skill already makes
between Git hooks and agent hooks:

| | Writes | Decides on | Good at |
|---|---|---|---|
| `permissions` rules | `.claude/settings.json`, key `permissions` | The **tool name** | The general posture, readable at a glance, auditable in a diff |
| Hook 9 | `scripts/agent-hooks/mcp-write-guard.sh` | The **arguments** | What a name cannot express — which repository, which table, which branch |

**The default posture: deny writes, allow reads.** It sorts by consequence, not by server: reading
an issue changes nothing, closing one does. Two postures were rejected and the report says why:

- `ask` for every `mcp__*` is safe and exhausting — the agent stops on every issue read, and within
  two days somebody allows everything to get work done. A gate that gets switched off protects less
  than one that was never installed.
- Trusting the server's own `readOnlyHint` is not an option: the specification says to treat
  annotations as untrusted, because the server that declares them is the one you would be watching.
  The contradiction report from the previous step **feeds this decision and never relaxes it**: a
  tool that declared itself read-only and contradicts itself is a reason to widen the deny list,
  and a tool that declared itself read-only credibly is still not a reason to narrow it.

Classify each registered server's tools by consequence, **reading the tool list from the server**,
not from memory. When a tool's name does not make the consequence obvious, it goes in the deny list
and the report says so — the failure that matters is allowing a write by accident, not denying a
read.

**Say in the report that this does not break `/sdlc-ia:github-plan-build`.** The deny list looks
like it would stop the delivery loop — it opens PRs, comments on issues, moves labels. It does not:
that skill goes through the `gh` CLI over Bash, not through the GitHub MCP server, so none of these
matchers apply to it. Without that sentence, the first person to read the deny list switches the
whole control off to unblock a skill that was never blocked.

**Merge, never clobber — and this file matters more than the others.** A `permissions` object may
already hold the user's own rules. Write only `mcp__*` matchers, leave every other entry untouched,
and show the before/after of that key in the report. If `permissions` already contains `mcp__*`
rules, do not overwrite them: report the difference and stop, the same way
`/sdlc-ia:instrument-github-repo` treats an existing ruleset.

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

**The permission rules get the same treatment, and they need both halves.** A rule set that denies
everything is as broken as one that denies nothing, and it is the one discovered later:

1. **Deny fires** — ask for a write-shaped MCP call that the rules cover and confirm it is refused,
   naming the rule. If it goes through, the matcher does not match: check the exact tool name
   (`mcp__<server>__<tool>`), which is the usual cause.
2. **Allow still works** — ask for a read-shaped call on the same server and confirm it runs
   without a prompt. A posture that also blocks reads turns the servers you just registered into
   dead weight, and the user finds out mid-task a week later.

Then hook 9 on top: trigger it with a call the **rules allow** but whose **arguments** should not
pass — that is the whole reason it exists next to them, and a hook that never fires because the
rules already caught everything is a hook to remove, not to keep.

MCP cannot be verified the same way — a freshly written `.mcp.json` leaves its servers at
`⏸ Pending approval` until the user trusts the workspace. Confirm the file parses, start each
stdio server once with `< /dev/null` to catch a dead flag early, confirm every `${ENV_VAR}` it
references actually appears in a real `.env` (not only `.env.example`) per
`references/mcp-servers.md`'s "Before you finish" section, and say plainly that this half of the
run was written, not proven.

---

## Phase 6 — Document and report

Update `AGENTS.md`, `README.md`, and (if present) `docs/infrastructure.md`/`docs/java.md` per
`references/report-and-docs.md` — what each doc needs, and when to skip it.

Report, in order: files created/modified (config and docs separately); the audit log's contents
and gitignore status, if installed; hooks **not** installed and why; MCP servers as
written-pending-approval with their resolved versions, each `${ENV_VAR}` they need flagged as
confirmed-in-`.env` or missing; **the annotation contradictions found per server, or that none
were found, or that they could not be read** — those three are different results and the report
says which; known false positives, hook by hook (leading with `cp .env.example .env` being denied);
that `.claude/settings.json` is Claude Code's alone — no other agent reads it.

When a server declares **no** annotations at all, say so in the words the specification implies:
with its defaults, every unannotated tool reads as **potentially destructive and open-world**. "No
annotations" is not "no findings", and a report that leaves that implicit is how a team concludes a
server is harmless because it said nothing.

**Then, last,** walk the **Try it** table in `references/verification-steps.md` §2 with the user —
one line per installed hook and registered server.

Do not commit. Leave the changes for the user to review.

---

## Reference

| Reference | Used by | What it covers |
|---|---|---|
| `references/discovery-checklist.md` | Phase 1 | The 10-item discovery checklist |
| `references/hook-catalog.md` + `references/hook-catalog-2.md` | Phase 3, Phase 4, Phase 5 | The nine hooks: what each does, why, what it costs, what it misses |
| `references/mcp-servers.md` | Phase 1, Phase 3, Phase 4 | Deriving the server menu from the repo, and the config shape of each |
| `references/verification-steps.md` | Phase 5, Phase 6 | Per-hook trigger/expected table, and the "Try it" walkthrough |
| `references/report-and-docs.md` | Phase 6 | Which doc gets what, and when to skip one |
| `references/troubleshooting.md` | Whenever something doesn't fire as expected | Symptom → likely cause → how to confirm |
| `templates/` | Phase 4 | The file skeletons |

## Rules

- Do NOT write to `.claude/settings.local.json`.
- Do NOT touch `permissions` without the explicit answer to scope question 6, and then only
  `mcp__*` matchers — every other entry in that key belongs to the user.
- Do NOT overwrite `mcp__*` rules that already exist. Report the difference and stop.
- Do NOT default the posture to `ask` for everything, and do NOT derive it from a server's own
  `readOnlyHint`: the specification says to treat annotations as untrusted.
- Do NOT report the permission rules as working without having seen a write denied **and** a read
  still allowed.
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
