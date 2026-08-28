# Apply, Verify, Report

> Called from **Phase 4**, **Phase 5** and **Phase 6** of `SKILL.md`. Confirmed against `gh`
> 2.98.0: `gh issue create --parent <number>` and `gh issue view --json subIssues,parent` are both
> real, current commands — this is not a speculative binding.

## Phase 4 — Apply

Confirm the destination chosen in Phase 3 first. Four steps, same names arkandia's own
`requirement-to-spec` uses for its binding table — resolved here for GitHub and for the file
system, the only two destinations this plugin supports:

| Step | File mode | Tracker mode (GitHub) |
|---|---|---|
| `CREATE-PARENT` | Write `docs/specs/<slug>/spec.md` from `templates/spec.md.template` | `gh issue create --title "<title>" --body "<spec body>" --label enhancement` — the spec's own content becomes the issue body |
| `CREATE-CHILD` | Write `docs/specs/<slug>/tasks.md` from `templates/tasks.md.template`, one entry per task | `gh issue create --title "<task title>" --body "<task body>" --parent <parent-issue-number>` — one sub-issue per task, native GitHub sub-issues, not a checklist inside the parent body |
| `LINK-PARENT` | Both files cross-reference each other by relative path in their first section | `--parent` at creation time already sets the relation both directions; nothing further to link |
| `SET-INITIAL-STATUS` | N/A — a file has no status field | Leave the sub-issues **open**, unassigned — `github-plan-build`'s own Step A is what takes an issue from open to in-progress, not this skill |

`<slug>` = kebab-case of the requirement's title (or its filename, if the document has no clear
title). Both files are created together in file mode — never one without the other, since each
references the other.

**Do not open a pull request. Do not touch git.** `gh issue create` talks to GitHub's API, not to
the local repository — it is not a git write, and this skill's boundary against touching git holds
regardless.

## Phase 5 — Verify

**File mode:** confirm both `docs/specs/<slug>/spec.md` and `docs/specs/<slug>/tasks.md` exist,
and that each file's cross-reference to the other resolves to a real relative path — a link to a
file that was supposed to be created in the same run and is not there is a Phase 4 failure to
report, not a Phase 5 pass.

**Tracker mode:** re-fetch what was just created — `gh issue view <parent> --json
subIssues,subIssuesSummary` — and confirm `subIssuesSummary.total` matches the number of tasks
written, and each entry in `subIssues` resolves to the issue just created (not a stale one from a
prior run). `gh issue view <child> --json parent` confirms the reverse direction. A relation that
does not show up on re-fetch is not verified — report it as written, not proven, the same
distinction `instrument-agent-java`'s Phase 5 draws for a freshly written but not-yet-approved MCP
server.

## Phase 6 — Report

Four sections, always present, empty where nothing applies — never omit a section because it has
nothing to say:

1. **Asked** — every question actually put to the user via `AskUserQuestion`, verbatim.
2. **Answered** — the resulting Decisions and Assumptions, cited from Phase 3, not re-derived.
3. **Out of scope** — anything the requirement mentioned that was explicitly excluded, with the
   reason (usually: "the user chose to leave this for later" or "not answerable without the
   missing attachment").
4. **Not read** — every attachment Phase 1 could not convert or open, by name. Repeats the Phase 1
   finding; does not add speculation about what a missing file might have said.

Close with a concrete next step naming the real destination just written — never a generic
"you can now proceed":

- Tracker mode: `Try it: /sdlc-ia:github-plan-build <parent issue number>`
- File mode: `Try it: open docs/specs/<slug>/spec.md and start there.`
