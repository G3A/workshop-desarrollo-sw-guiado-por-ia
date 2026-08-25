# What to update, and where

> Called from **Phase 6** of `SKILL.md`. Update what exists; do not create the doc pack — that is
> `/sdlc-ia:agent-context-java`'s job. Write with `Edit`/`Write`, never a Bash heredoc — this
> phase's own prose contains `.env`, `id_rsa`, `secrets.json`, which hook 1 would deny inside a
> `cat <<EOF` command.

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
