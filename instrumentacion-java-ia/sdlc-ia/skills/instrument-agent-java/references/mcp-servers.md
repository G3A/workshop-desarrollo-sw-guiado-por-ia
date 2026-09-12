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
| **A browser server** | a user interface — templates, static assets, a front-end subproject, view-returning controllers, or an existing browser test suite (checklist item 8b) | The fourth verification layer: the agent can look at what it built |

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

**Never write to `.claude/settings.local.json`.** It belongs to the user.

**`permissions` is the other half of this file, and it is not optional reading.** Registering a
server here widens what the agent can reach on its own; the `mcp__*` rules of Phase 4 are the only
deterministic way to narrow it again. Writing one without the other leaves the skill's own ordering
rule — *MCP first, hooks second, because MCP only adds capability and hooks take it away* — executed
halfway. The posture, the two rejected alternatives and the merge rules are in
`templates/permissions.json.template`.

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

## The browser server: ask which one, never pick for them

This is the **fourth verification layer**, and the only one where the agent cannot check its own
work today: it runs the local gates, it reads CI, it reads the security scan — and then declares
"the page works" without ever having looked at it.

Offer it **only when checklist item 8b found a user interface.** In a pure REST service a browser
server is dead weight the team pays for in context on every session.

**Two servers, and they answer different questions.** Ask which one; do not default. The question
only appears for repositories that already have a UI, so it is not one more question for everyone.

| | **Playwright MCP** | **Chrome DevTools MCP** |
|---|---|---|
| Package | `@playwright/mcp` | `chrome-devtools-mcp` |
| Maintained by | Microsoft — same repository and licence as Playwright | The Chrome DevTools team, at Google |
| The question it answers | "does it work, and does it look right?" | "why is it slow, and why did that request fail?" |
| How it reads the page | **Accessibility-tree snapshots by default**, not images | Performance traces, network requests, console messages with source-mapped stack traces |
| Pick it when | The repo already uses Playwright, or the work is building and checking UI | The work is diagnosing load time, failing requests or console errors |

Two facts worth putting in the question, because they change the answer:

- **Playwright MCP reads the accessibility tree, not screenshots.** The agent gets structure,
  which is cheaper and far more reliable for clicking the right thing — but "does it *look* right"
  needs an explicit screenshot request. A team expecting the agent to notice a visual regression on
  its own will be disappointed, and should hear that before choosing.
- **Neither needs a browser installed first.** Playwright MCP downloads its browser on first use.
  Do not add Playwright to the repository's dependencies to "prepare" for this — choosing a team's
  testing stack is not this skill's call, the same rule that stops `instrument-project-java` from
  installing Testcontainers.

```json
"ark_playwright": {
  "type": "stdio",
  "command": "npx",
  "args": ["-y", "@playwright/mcp@{{PLAYWRIGHT_MCP_VERSION}}", "--isolated", "--allowed-origins", "{{ALLOWED_ORIGINS}}"]
}
```

```json
"ark_chrome_devtools": {
  "type": "stdio",
  "command": "npx",
  "args": ["-y", "chrome-devtools-mcp@{{CHROME_DEVTOOLS_MCP_VERSION}}"]
}
```

**The vendor's `@latest` does not apply here.** Playwright's own installation page shows
`@playwright/mcp@latest`; this skill pins, like every other stdio entry — resolve with
`npm view <package> version` in the same run that writes the file. The reason is in **The rules
that do not vary** above and does not weaken because the publisher is Microsoft.

### The flag that is not a boundary, and why it matters here

Playwright MCP takes `--allowed-origins` and `--blocked-origins` (semicolon-separated, blocked
evaluated first), `--isolated` (profile kept in memory, never written to disk), `--browser` and
`--caps`. Set the origins to the app's own hosts and `--isolated` by default: a browser profile on
disk accumulates logged-in sessions the agent then inherits.

**But say plainly what that buys, because Playwright's own documentation does.** It states that the
origin lists and the file-access guardrail are *convenience defenses to catch unintended access,
not a security boundary* — they do not stop redirects and can be deliberately circumvented — and
that **real isolation requires client-level permissions**.

That is this skill's own axis, stated by the vendor about its own product: **MCP only adds
capability; hooks and `permissions` are the only things that take it back.** So a browser server is
exactly the case that makes the `mcp__*` rules of Phase 4 non-optional rather than a nicety. Write
both halves or neither, and say so in the report — a team that reads `--allowed-origins` in a diff
and concludes the agent is fenced in has drawn the wrong conclusion from a real flag.

### The second risk, and it is not the network

A browser server **pulls web page content into the model's context**. That is the classic
prompt-injection surface: text on a page the agent visited is untrusted input that now sits
alongside the user's instructions. It is the same reason MCP tool annotations are treated as hints
and not as facts.

Nothing in this skill solves that. What it can do is keep the blast radius small — origins scoped
to the app's own hosts, `--isolated`, and the write-denying `mcp__*` permission rules — and say in
the report that the remaining exposure is real. A caveat stated is a caveat the team can decide
about.
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
- **Warn about the browser server's first run.** It downloads a browser the first time it is
  used — hundreds of megabytes, on a connection that may be someone's phone tether. Nothing is
  broken; it just looks like a hang. Put it in the report, not in a footnote nobody reads at the
  moment it happens.
- **Check that each `${ENV_VAR}` actually resolves, not just that it is named.** A variable
  documented in `.env.example` is not the same as one exported in the team's real `.env` — the
  server fails at connect time either way, but "written pending approval" reads as done and hides
  that gap until someone actually opens the workspace. For each `${VAR}` (and `${VAR:-}` defaults
  do not count, they are deliberately optional): if a real `.env` file exists, confirm the name
  appears there too; if it does not, or the variable is missing from it, report it explicitly —
  "`GITHUB_PAT` is in `.env.example` but not in `.env` — the GitHub server will fail to connect
  until it is set" — rather than assuming a name in `.env.example` means the value is live.
  Never read the value itself, only whether the name is present.
