# Discovery checklist

> Called from **Phase 1** of `SKILL.md`. Glob, Grep, Read and read-only Bash only. Confirm this is
> a Java repository first: `pom.xml`, `mvnw`, `mvnw.cmd`. If nothing matches, stop and say so.

Work through, and report as a table (artifact, status, what you found):

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
10. **The team's OS.** Ask only if the repo gives no signal. Two separate questions hide inside
    "Windows," and each decides something different:
    - Is anyone on Windows **without** Git Bash? Then Claude Code falls back to PowerShell for
      its `Bash`-labelled tool, and the `.sh` hooks — which need `bash` to run at all — silently
      do nothing. This is a reason to warn, not a reason to change what gets offered.
    - Is anyone on Windows **at all**, Git Bash present or not? A session on Windows can have a
      separate `PowerShell` tool alongside `Bash`, invoked for commands the agent judges more
      natural in that shell — and every hook script only sees the tool calls its own matcher
      names. This is hook 8's precondition: offer it only when the team is genuinely on Windows,
      the same way hook 7 is offered only when Flyway/Liquibase migrations exist.

Two findings change the menu and must be surfaced in Phase 1, not silently applied: a hook already
registered on an event you are about to write to, and a failed precondition.
