# MCP servers: deriving the menu, and writing the file

> Called from **Phase 1**, **Phase 3** and **Phase 4** of `SKILL.md`.

MCP is what turns "describe the system to the agent" into "let the agent query the system
directly." Do not present a fixed list — offer a server only when the repository gives a signal
for it.

## The detection table

| Server | Offer when Phase 1 found | Gives the agent |
|---|---|---|
| **GitHub** | a remote on `github.com`, or `.github/workflows/` | Issues, pull requests, Actions runs |
| **Context7** | always | Up-to-date, version-aware library documentation |
| **DBHub** | a JDBC/R2DBC connection string, or a `DataSource`/`spring.datasource.url` property, pointing at a real database | Read access to the actual schema and data |

**GitHub is the primary server for this skill** — a Java/GitHub pairing is the common case this
skill targets. **Context7 is offered unconditionally**: it is the answer to a model writing
against a Spring Boot or Maven-plugin API that was renamed two releases ago.

## The GitHub server: two ways to register it

The GitHub MCP server (`https://api.githubcopilot.com/mcp`) supports interactive OAuth login,
which is what `claude mcp add --transport http github https://api.githubcopilot.com/mcp` sets up —
the command this monorepo's own `proceso-operacional-con-ia/comandos.json` documents (node `a2`).
That command registers the server and the user completes login in the browser on first use; no
token is ever written to a file.

**This skill writes `.mcp.json` directly instead**, because the file must be reviewable in a diff
and reproducible for the whole team without each person separately running the `claude mcp add`
command. Use a header-based bearer token, resolved from an environment variable the user exports —
never a literal token:

```json
"ark_github": {
  "type": "http",
  "url": "https://api.githubcopilot.com/mcp",
  "headers": { "Authorization": "Bearer ${GITHUB_PAT}" }
}
```

Tell the user both options exist in the report: `claude mcp add` for a quick, per-person OAuth
setup that is not committed, or the committed `.mcp.json` entry above for a setup the whole team
shares. Default to the committed entry, since project scope is the point of this phase.

## The rules that do not vary

**No literal secrets. Ever.** Every credential is `${ENV_VAR}`. `.mcp.json` is committed, so a
token written into it is a token in the history.

**Every stdio package version resolved at write time, then pinned.**
`npx -y <package>@<resolved version>`, never a bare `npx -y <package>`. Resolve with
`npm view <package> version` in the same run that writes the file. An unpinned entry executes
whatever npm has published since, under the user's own permissions, on every machine that clones
the repo, with nothing in the history recording the change.

**`${CLAUDE_PROJECT_DIR}` does not give you an absolute path in this file.** Claude Code sets that
variable in the **server's** environment, not its own, so a project-scoped `.mcp.json` has nothing
to expand from at read time — `${CLAUDE_PROJECT_DIR:-.}` always resolves to its default, `.`, a
relative path. A SQLite-style DSN built from it fails with "unable to open database file"; only an
absolute path connects. Treat a machine-specific path like a credential: a dedicated environment
variable with no default, and the export line in `README.md`.

```json
"ark_dbhub": {
  "type": "stdio",
  "command": "npx",
  "args": ["-y", "@bytebase/dbhub@{{DBHUB_VERSION}}", "--transport", "stdio", "--dsn", "${APP_DSN}"]
}
```

| `spring.datasource.url` prefix found | `APP_DSN` |
|---|---|
| `jdbc:postgresql:` | `postgres://user:pass@host:5432/db?sslmode=require` |
| `jdbc:mysql:` / `jdbc:mariadb:` | `mysql://user:pass@host:3306/db?sslmode=require` |
| `jdbc:sqlserver:` | `sqlserver://user:pass@host:1433/db?sslmode=disable` |
| `jdbc:h2:` (file-based) | absolute path required — treat like the SQLite case above |

**Do not pass `--readonly`** to DBHub — it was removed; the server now exits with
`--readonly flag is no longer supported`. Confirm the current read-only mechanism (a `dbhub.toml`
with a `[[tools]]` section, at the time of writing) before offering it, and say in the report that
an agent with a database connection reads whatever the DSN points at.

**Never write to `.claude/settings.local.json`, and never touch `permissions`.** Both belong to the
user.

**Merge, never replace.** An existing `mcpServers` object holds the team's work.

**Context7's key is the one deliberate exception to "never default a credential."**
`${CONTEXT7_API_KEY:-}` degrades to the anonymous tier rather than failing to start:

```json
"ark_context7": {
  "type": "stdio",
  "command": "npx",
  "args": ["-y", "@upstash/context7-mcp@{{CONTEXT7_MCP_VERSION}}"],
  "env": { "CONTEXT7_API_KEY": "${CONTEXT7_API_KEY:-}" }
}
```

## Writing the file does not connect the servers

A newly written `.mcp.json` leaves its servers at **`⏸ Pending approval`** until the user trusts
the workspace, and `enableAllProjectMcpServers`/`enabledMcpjsonServers` committed in
`.claude/settings.json` are **ignored in an untrusted folder** — a freshly cloned repo cannot
approve its own servers. Report it that way:

> `.mcp.json` written with N servers — **pending approval**. Run `claude` in this repository and
> accept the workspace trust dialog, then `/mcp` to confirm each server connects.

**Phase 5 cannot verify MCP the way it verifies hooks.** Hooks take effect immediately; MCP
servers do not. Say which half of the run was proven and which half was only written.

## Before you finish

- Confirm the file parses: `python3 -c "import json;json.load(open('.mcp.json'))"`.
- Start each stdio server once with `< /dev/null` so it exits instead of hanging on a handshake
  that never arrives, and confirm it does not immediately fail on an unsupported flag.
- Grep your own output for anything that looks like a credential rather than a `${VAR}`.
- List every environment variable introduced, so Phase 6 can put them in `README.md`.
- **Check that each `${ENV_VAR}` actually resolves, not just that it is named.** A variable
  documented in `.env.example` is not the same as one exported in the team's real `.env` — the
  server fails at connect time either way, but "written pending approval" reads as done and hides
  that gap until someone actually opens the workspace. For each `${VAR}` (and `${VAR:-}` defaults
  do not count, they are deliberately optional): if a real `.env` file exists, confirm the name
  appears there too; if it does not, or the variable is missing from it, report it explicitly —
  "`GITHUB_PAT` is in `.env.example` but not in `.env` — the GitHub server will fail to connect
  until it is set" — rather than assuming a name in `.env.example` means the value is live.
  Never read the value itself, only whether the name is present.
