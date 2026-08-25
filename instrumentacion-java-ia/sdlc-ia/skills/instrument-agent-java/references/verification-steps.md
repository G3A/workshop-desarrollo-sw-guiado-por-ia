# Verification steps

> Called from **Phase 5** and **Phase 6** of `SKILL.md`.

## 1. Trigger each installed hook (Phase 5)

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

## 2. Try it, with the user (Phase 6)

One line per installed hook and registered server:

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
