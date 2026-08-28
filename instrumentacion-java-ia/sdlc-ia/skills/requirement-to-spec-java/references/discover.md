# Discover and Prerequisites

> Called from **Phase 1** and **Phase 2** of `SKILL.md`. Read-only: Read, Glob, Grep, and the
> single conversion command in Phase 1 step 1. Nothing here writes a file or opens a tracker.

## Phase 1 — Discover (silent)

Work through in order. Report the whole phase as one table (artifact/signal, status, concrete
finding) — never a running commentary as you go.

1. **Convert the document to Markdown.** Check `npx --version` first; if absent, record it as a
   missing prerequisite for Phase 2 and fall back to reading the file directly if the format
   allows it (plain text, Markdown — not a `.docx`/`.pdf`/`.xlsx` you cannot parse without a
   converter). When `npx` is present, resolve a converter appropriate to the input
   (`npx --yes markitdown <path>` covers Word/PDF/Excel/PowerPoint uniformly; note the resolved
   package and version in the report, same pinning discipline as `instrument-agent-java`'s MCP
   servers). Set a real timeout — a hung conversion is a Phase 2 finding, not a silent wait.
2. **Read repo context.** `AGENTS.md`, `CLAUDE.md`, `docs/architecture.md`, `docs/java.md`, ADRs.
   This is where you learn the domain vocabulary the requirement will use loosely and the repo
   names precisely — carry that mapping into Phase 3's questions instead of asking the user to
   restate it.
3. **Detect public contracts the requirement may touch.** Not a generic "does this look
   important" pass — search for the concrete names (endpoints, tables, module names, domain
   terms) the requirement's own text mentions, then confirm each exists in the repo:
   - REST surface: `@RestController` classes, `@GetMapping`/`@PostMapping`/`@PutMapping`/
     `@DeleteMapping`/`@RequestMapping` methods. A requirement naming "the chat endpoint" or "the
     feedback API" resolves to a real class and method here, not a guess.
   - Persistence surface: `@Entity` classes and the migration that created their table
     (`src/main/resources/db/migration/*.sql` for Flyway, `db/changelog/` for Liquibase) — a
     requirement that adds or renames a field touches both the entity and the next migration.
   - A generated contract, if the repo has one: an OpenAPI/Swagger spec file, or `.proto` under
     `src/main/proto/` for gRPC. Not every Java/Spring repo has one — absence is a normal finding,
     not a gap to explain away.
   For each contract surface the requirement's own text plausibly touches, record the exact
   class/method/table/field name and its file path. A requirement that never mentions anything
   matching a given surface leaves that surface unrecorded — do not force a match to fill the row.
4. **Detect documentation the change would make false.** For each contract finding in step 3,
   grep `docs/` and ADRs for the same names. Cite the file and the line, quoted if short enough to
   quote — "some documentation may be affected" is not a finding, a line number is. Never guess
   which document is affected by its category ("the API docs" when no specific line was found);
   report only what a grep actually surfaced.
5. **Detect available trackers.** Same signal `github-plan-build` uses in its own Phase 1: a
   `github.com` remote, or `.github/workflows/`. This plugin talks to GitHub through `gh` only —
   report "GitHub" as a candidate destination when the signal is present, and "local file" always,
   regardless.
6. **Detect a database MCP server, by shape not by name.** Read `.mcp.json` for a server whose
   config shape matches a database connection (a `--dsn`/connection-string argument, not a
   specific server name — `instrument-agent-java` may have registered it as `ark_dbhub`, or the
   team may have named it anything). Its presence matters for Phase 3's tabular cross-check
   question; note whether one exists, nothing more at this stage.

**Report, per finding row:** artifact/signal name, status (`present` / `absent` / `unreadable`),
and the concrete evidence — a file path and line, a resolved tool version, or "not found" stated
plainly. List every attachment the source document references that could not be read, by name,
here — Phase 6 repeats this list, it does not invent it fresh.

## Phase 2 — Prerequisites

Report the result of Phase 1's own tool checks — `npx --version`, and, if tracker mode is even
possible, whatever the chosen tracker's own CLI needs (for this plugin, `gh auth status`, already
covered by node `a1` of the process this repo documents). **Install nothing yourself.** If a
prerequisite is missing, say so and say what installing it would look like — the user's call, not
this skill's.
