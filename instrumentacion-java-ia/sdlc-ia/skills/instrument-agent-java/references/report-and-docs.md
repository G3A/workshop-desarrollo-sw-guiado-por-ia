# What to update, and where

> Called from **Phase 6** of `SKILL.md`. Update what exists; do not create the doc pack — that is
> `/sdlc-ia:agent-context-java`'s job. Write with `Edit`/`Write`, never a Bash heredoc — this
> phase's own prose contains `.env`, `id_rsa`, `secrets.json`, which hook 1 would deny inside a
> `cat <<EOF` command.

1. **`AGENTS.md`** — an index, not the home of the tables: `agent-context-java` keeps it under ~80
   lines. It gets an `Agent hooks` section and an `MCP` section (under their equivalent titles in
   the file's language), **each one line and a link** to the tables in item 3 — how many hooks and
   which servers, nothing more. Update those sections if they exist; never append a second copy.
   If an earlier run left the full tables in `AGENTS.md`, move them to `docs/infrastructure.md`
   and leave the one-line link. Only when `docs/infrastructure.md` does not exist do the tables go
   in `AGENTS.md`, and the report names the missing doc pack.
2. **`README.md`** — Git Bash and Maven in the prerequisites, the environment variables the MCP
   servers expect, the workspace-trust step (`claude` then `/mcp`), and — if hook 5 was
   installed — one sentence that the audit log holds full tool input and is gitignored.
3. **`docs/infrastructure.md`** and **`docs/java.md`**, if present — in `docs/infrastructure.md`,
   the hooks table (each hook, what it blocks, its matcher, which script) and the MCP table (each
   server, its transport, what it reaches, the environment variable it needs), re-counted against
   `.claude/settings.json` rather than copied from a previous report; and (only if hook 6 or 7 was
   installed) the version-centralisation or migration-immutability
   rule now enforced at edit time. Skip a document entirely if neither hook that would touch it
   was installed, and say so.
