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

Glob, Grep, Read and read-only Bash only. Confirm this is a Java repository first: `pom.xml`,
`mvnw`, `mvnw.cmd`. If nothing matches, stop and say so.

Then work through, and report as a table (artifact, status, what you found):

1. **The pom(s).** In a monorepo the Java project may live in a subfolder — record its path as
   `POM_PATH` (hook 2 needs it: `-f {{POM_PATH}}` resolves relative to the repo root). For a
   reactor with multiple modules, record which pom the root build runs against.
2. **`.claude/settings.json`.** Read its `hooks` key: for each event already present, record the
   matcher and the script it points at. Look for `scripts/agent-hooks/` or `.claude/hooks/` too —
   reuse an existing home rather than opening a second one. Never write to
   `.claude/settings.local.json`; read it only if you need it to understand the setup.
3. **`.mcp.json`.** Record every server already registered, and flag a literal (non-`${VAR}`)
   credential as a finding, not something to silently fix.
4. **The `Makefile`, if one exists.** Which of `format`, `lint`, `audit`, `check`, `test` actually
   exist, and read the body of each — a `format` target that does not call Spotless changes what
   hook 2 has to do. `make audit` is what hook 4 should call when present; otherwise fall back per
   `references/hook-catalog.md`.
5. **The formatter.** Is `spotless-maven-plugin` declared (build plugin or `pluginManagement`)?
   Without it, hook 2 has no format rules to run — say so and recommend
   `/sdlc-ia:instrument-project-java` first, then hide the hook. If present, time the scoped
   command on one real `.java` file:
   `time mvn -q -f <pom> spotless:apply -DspotlessFiles=.*<escaped-relative-path>` (see
   `references/hook-catalog.md`, hook 2, for how the pattern is built). Quote the seconds in
   Phase 3.
6. **Hook preconditions.**

   | Hook | Precondition | Check |
   |---|---|---|
   | 6 | centralised dependency versions | `grep -c '<dependencyManagement>' pom.xml`, or a parent BOM import |
   | 7a | Flyway | `find . -path '*/src/main/resources/db/migration' -type d` |
   | 7b | Liquibase | `find . -path '*/src/main/resources/db/changelog' -type d` |

   A failed precondition is reported and hidden, not worked around — say which and why.
7. **Git facts.** Remote host (`github.com` → GitHub MCP), default branch
   (`git symbolic-ref refs/remotes/origin/HEAD`, not an assumption of `main`), and any long-lived
   branches (`develop`, `release/*`) — these decide hook 3's `PROTECTED_BRANCHES`.
8. **The database.** `grep` for `spring.datasource.url` / a JDBC URL prefix in
   `application*.yml`/`.properties`. More than one profile is normal (H2 locally, Postgres in
   production) — report every one found and ask which the agent should reach.
9. **Documentation and its language.** `AGENTS.md`, `CLAUDE.md`, `README.md`, `docs/` — note
   whether `AGENTS.md` already has `Agent hooks`/`MCP` sections (Phase 6 updates them, never
   duplicates) and which language the prose is in (Phase 6 must not switch mid-document).
10. **The team's OS.** Ask only if the repo gives no signal. What actually matters is whether
    anyone is on Windows without Git Bash, since the hook scripts are bash and Claude Code falls
    back to PowerShell without it — at which point the `.sh` hooks silently do nothing.

Two findings change the menu and must be surfaced here, not silently applied: a hook already
registered on an event you are about to write to, and a failed precondition.

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
2. **The hook scripts**, into `scripts/agent-hooks/`, starting with `_lib.sh`:

   | Template | Becomes | Only when |
   |---|---|---|
   | `templates/hooks/_lib.sh.template` | `scripts/agent-hooks/_lib.sh` | always |
   | `templates/hooks/secret-read-guard.sh.template` | `…/secret-read-guard.sh` | hook 1 |
   | `templates/hooks/format-on-edit.sh.template` | `…/format-on-edit.sh` | hook 2 |
   | `templates/hooks/block-dangerous-bash.sh.template` | `…/block-dangerous-bash.sh` | hook 3 |
   | `templates/hooks/dependency-sweep.sh.template` | `…/dependency-sweep.sh` | hook 4 |
   | `templates/hooks/audit-log.sh.template` | `…/audit-log.sh` | hook 5 |
   | `templates/hooks/version-pin-guard.sh.template` | `…/version-pin-guard.sh` | hook 6 |
   | `templates/hooks/generated-files-guard.sh.template` | `…/generated-files-guard.sh` | hook 7 |

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
**through the real tool call**, confirm the outcome and that the message names the problem,
restore from the snapshot (not `git checkout` — most of what you touch is untracked). After every
trigger, `git status` must look exactly as it did before it.

| # | Hook | Trigger | Expected |
|---|---|---|---|
| 1 | Secret read-guard | Five steps, **in order**, because creating and deleting the probe are denied too: (1) `Edit .gitignore` append `hooktest/`; (2) `Write hooktest/.env`; (3) `Read hooktest/.env` — **denied, this is the proof**; (4) `Bash: rm -rf hooktest`; (5) `Edit .gitignore` remove the line. Put the probe in a throwaway directory, never the repo root — the guard tokenises Bash and cannot tell a path from a mention of one, so `rm -f .env` alone would also be denied | Step 3 denied, naming credential files. Also confirm a true negative: `Read .gitignore` goes through |
| 2 | Format on edit | `Write` a `.java` file with a deliberately wrong indent/import order | `mvn -q -f <pom> spotless:apply -DspotlessFiles=.*<the file> --verify-no-changes` (or the plugin's check goal) exits 0 afterward — parity with the repo's own gate, not a fixed list of fixes |
| 3 | Dangerous-command blocker | `Bash: git reset --hard HEAD` | Denied, naming `git stash`. Confirm a true negative: `git status` goes through |
| 4 | Dependency sweep | Run directly: `printf '{"hook_event_name":"SessionStart","source":"startup"}' \| bash scripts/agent-hooks/dependency-sweep.sh; echo "exit=$?"` | Advisory lines, or silence — `exit=0` either way. Also run the sweep command alone and confirm its own exit is 0, or the silence is not proof of a clean repo |
| 5 | Audit log | Any tool call, then `tail -3 logs/audit.log` | One tab-separated line, 5 fields, last one not `-`. Then `git check-ignore -v logs/audit.log` — **no output means it is not ignored; stop and fix `.gitignore` before continuing** |
| 6 | Version-pin guard | Add a literal `<version>` to an existing `<dependency>` in `pom.xml` (snapshot first — this is a committed file; restore with `git checkout --`) | A warning naming the pom and the count |
| 7 | Generated-file guard | `Edit` an **existing** file under `src/main/resources/db/migration/` (or `db/changelog/`) | Denied, naming "add the next migration" instead. Also confirm a true negative: `Write` a **new** path in the same directory goes through — this hook is existence-based, not directory-based |

**Do not report success with a hook that did not fire.** Fix it, or remove it and say so.

MCP cannot be verified the same way — a freshly written `.mcp.json` leaves its servers at
`⏸ Pending approval` until the user trusts the workspace. Confirm the file parses, start each
stdio server once with `< /dev/null` to catch a dead flag early, and say plainly that this half of
the run was written, not proven, per `references/mcp-servers.md`.

---

## Phase 6 — Document and report

**Update what exists; do not create the doc pack** — that is `/sdlc-ia:agent-context-java`'s job.
Write with `Edit`/`Write`, never a Bash heredoc — this phase's own prose contains `.env`, `id_rsa`,
`secrets.json`, which hook 1 would deny inside a `cat <<EOF` command.

1. **`AGENTS.md`** — an `Agent hooks` section (each hook, what it blocks, which script) and an
   `MCP` section (each server, what it reaches, the environment variable it needs). Update
   existing sections rather than appending a duplicate.
2. **`README.md`** — Git Bash and Maven in the prerequisites, the environment variables the MCP
   servers expect, the workspace-trust step (`claude` then `/mcp`), and — if hook 5 was
   installed — one sentence that the audit log holds full tool input and is gitignored.
3. **`docs/infrastructure.md`** and **`docs/java.md`**, if present — MCP transports/credentials,
   and (only if hook 6 or 7 was installed) the version-centralisation or migration-immutability
   rule now enforced at edit time. Skip a document entirely if neither hook that would touch it
   was installed, and say so.

Report, in order: files created/modified (config and docs separately); the audit log's contents
and gitignore status, if installed; hooks **not** installed and why; MCP servers as
written-pending-approval with their resolved versions; known false positives, hook by hook
(leading with `cp .env.example .env` being denied); that `.claude/settings.json` is Claude Code's
alone — no other agent reads it.

**Then, last, a Try it table** — one line per installed hook and registered server:

| Installed | Ask the agent / run | You should see |
|---|---|---|
| Secret guard | "read `.env`" (create one first with Write) | A refusal naming credential files |
| Format on edit | Ask for a method added to a `.java` file with sloppy indentation | The file comes back formatted |
| Dangerous commands | "run `git reset --hard HEAD`" | A refusal pointing at `git stash` |
| Dependency sweep | Start a new session | Advisories, or nothing on a clean repo |
| Audit log | Any request, then `tail -3 logs/audit.log` | One line per tool call |
| Version-pin guard | Ask it to add a dependency with a literal version | A warning naming the pom |
| Generated-file guard | Ask it to edit an existing Flyway migration | A refusal naming "add the next migration" |
| Any MCP server | `/mcp` | Connected, not `⏸ Pending approval` |

Do not commit. Leave the changes for the user to review.

---

## Troubleshooting

| Symptom | Almost always | Confirm with |
|---|---|---|
| A guard never fires, and never errors | `set -o pipefail` in the script | Remove it; every script here uses `set -u` alone |
| A guard fires on things it should not | Matching the whole stdin payload instead of a field | Match `tool_input.file_path`, or tokenise `tool_input.command` |
| A path check never matches on Windows | `tool_input.file_path` arrives with backslashes | Use `json_path`, not `json_raw`, for anything path-shaped |
| Hooks do nothing at all on Windows | Git Bash is absent; Claude Code fell back to PowerShell | `bash --version` |
| `settings.json` edits seem ignored | The file no longer parses | `python3 -c "import json;json.load(open('.claude/settings.json'))"` |
| An MCP server stays `⏸ Pending approval` | The workspace is not trusted | Run `claude`, accept the trust dialog, then `/mcp` |
| `${CLAUDE_PROJECT_DIR}` expands to `.` in `.mcp.json` | It is set in the server's environment, not Claude Code's | Use a dedicated env var with no default for an absolute path |
| `format-on-edit` reformats nothing | The `-DspotlessFiles` pattern did not match, or `spotless-maven-plugin` is not declared | Re-check the pattern against `mvn -f <pom> help:evaluate -Dexpression=project.basedir`; confirm the plugin exists |
| `version-pin-guard` fires on every dependency | Installed without `<dependencyManagement>`/a BOM in the pom | Remove it; the precondition is centralised versions |
| `generated-files-guard` blocks a brand-new migration | The script is checking the directory, not existence | Confirm the delivered template checks `[ -f "$FILE" ]`, not just the path pattern |
| An MCP server never starts, nothing looks wrong in `.mcp.json` | A flag the pinned version does not accept | `npx -y <package>@<pinned version> --help < /dev/null` |

---

## Reference

| Reference | Used by | What it covers |
|---|---|---|
| `references/hook-catalog.md` | Phase 3, Phase 4, Phase 5 | The seven hooks: what each does, why, what it costs, what it misses |
| `references/mcp-servers.md` | Phase 1, Phase 3, Phase 4 | Deriving the server menu from the repo, and the config shape of each |
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
