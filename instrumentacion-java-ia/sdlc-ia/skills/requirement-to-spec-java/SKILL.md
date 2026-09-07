---
name: requirement-to-spec-java
description: >
  Convert a business requirement document (Word, PDF, Excel, Markdown, plain text) into a
  specification and a task breakdown, for a Java/Spring repository. Detects which REST
  controllers, JPA entities, and database migrations the requirement touches; flags existing
  documentation the change would make false, citing exact lines; asks scope questions only where
  the document and the repo leave a real gap. Writes to `docs/specs/<slug>/` or to a GitHub issue
  with sub-issues, whichever the user picks. Never writes code, never opens a PR, never touches
  git. Invoke with `/sdlc-ia:requirement-to-spec-java <path to the requirement document>`.
argument-hint: "<path to requirement document>"
disable-model-invocation: true
allowed-tools: Read, Glob, Grep, Write, AskUserQuestion, Bash(command -v pandoc*), Bash(pandoc*), Bash(gh auth status*), Bash(gh issue create*), Bash(gh issue edit*), Bash(gh issue view*), Bash(gh issue list*), Bash(gh api repos/*/issues*), Bash(gh label list*), Bash(gh repo view*)
---

# requirement-to-spec-java — Business document → spec and tasks, before a ticket exists

You are turning a **requirement document that is not yet a ticket** into something
`/sdlc-ia:github-plan-build` (or a person) can act on: a specification, and a breakdown of tasks.
This runs **before** "tomar issue" — the actividad 17 gap the manual `manuales/manual-feedback-chat`
names explicitly: whoever writes the issue today does it by exploring the repo by hand. This skill
is the reproducible version of that exploration.

**Never writes code. Never opens a PR. Never touches git.** That boundary is what makes it safe to
run early, on a document nobody has committed to yet — the same boundary the plugin's other
delivery skill, `github-plan-build`, deliberately does not share (it pushes branches and opens
PRs, by design, once a plan is approved).

It is Java/Spring-aware where detection needs to be, and stack-agnostic everywhere else — same
split as `agent-context-java`.

## The six phases

Read `references/discover.md` now for Phases 1–2, `references/scope-questions.md` for Phase 3,
and `references/bindings-and-verify.md` for Phases 4–6 — this file is the map, not the body.

| Phase | Does | Detail in |
|---|---|---|
| 1 — Discover (silent) | Convert the document, read repo context, detect contracts/docs/trackers/DB | `references/discover.md` |
| 2 — Prerequisites | Report what Phase 1's tool checks found; install nothing | `references/discover.md` |
| 3 — Agree on scope | Max 4 questions per call, six always-active requirement categories | `references/scope-questions.md` |
| 4 — Apply | Write `docs/specs/<slug>/` or a GitHub issue with sub-issues, per the chosen destination | `references/bindings-and-verify.md` |
| 5 — Verify | File mode: both files exist, links resolve. Tracker mode: re-fetch, confirm the relation | `references/bindings-and-verify.md` |
| 6 — Report | Four sections always: Asked, Answered, Out of scope, Not read | `references/bindings-and-verify.md` |

## Philosophy (hold these throughout)

- **Never invent content.** A missing attachment is reported as missing — never described as
  "probably showing X." A requirement that does not say something does not get that something
  assumed on its behalf.
- **Cite, don't categorise.** When a change would make existing documentation false, name the
  file and the line, quote it if short. "Some docs may need updating" is not a finding — a citable
  line is.
- **Ask what the document and the repo could not answer between them — nothing else.** Four
  questions maximum per call, no jargon a business reader would not already use. See
  `references/scope-questions.md` for what stays always-on regardless of the document.
- **Functional work first, documentation follows what it describes.** A task list that puts
  "update the README" before the feature it documents has the order backwards.
- **Never decide for the user**: whether a public contract may break, whether stale documentation
  gets updated now or later, what counts as out of scope, whether a validation criterion blocks
  release. Surface each with its evidence; the decision is the user's.
- **Everything you write is in English** — scripts, structural scaffolding, hook-style messages —
  regardless of the conversation's language, matching every other skill in this plugin. The
  exception is the spec and tasks documents themselves: match the language `AGENTS.md`/existing
  `docs/` are written in, the same rule `agent-context-java` follows.
- **Never commit.** File mode leaves new files unstaged; tracker mode's `gh issue create` is not a
  git write — it never touches the working tree.

## Where output goes

Two destinations, chosen in Phase 3, never assumed:

- **File mode** — `docs/specs/<slug>/spec.md` and `docs/specs/<slug>/tasks.md`, bilingual
  templates in `templates/es/` and `templates/en/` (same convention as `agent-context-java`):
  Spanish by default, English if `AGENTS.md`/`docs/` are already in English.
  `<slug>` is the kebab-case of the requirement's title or filename.
- **Tracker mode** — a GitHub issue carrying the spec, with one sub-issue per task, linked
  parent/child. Reuses `github-plan-build`'s `TICKET` binding vocabulary (`gh issue`) rather than
  inventing a second way to talk to GitHub — see `references/bindings-and-verify.md`.

Report both options in Phase 3 (plus "local file" if no tracker signal exists) and let the user
pick; never default to one silently.

## Closing line

End Phase 6 with a concrete next step, naming the real destination just written:

> Try it: `/sdlc-ia:github-plan-build <issue number>` — or, in file mode, open
> `docs/specs/<slug>/spec.md` and start there.

## Reference

| Reference | Used by | What it covers |
|---|---|---|
| `references/discover.md` | Phase 1, Phase 2 | Document conversion, repo-context reading, Java contract detection, doc-invalidation citing |
| `references/scope-questions.md` | Phase 3 | The six always-active categories, `AskUserQuestion` mechanics, Decisions/Assumptions |
| `references/bindings-and-verify.md` | Phase 4, Phase 5, Phase 6 | File vs. tracker application, the binding table, verification, the report shape |
| `templates/` | Phase 4 | `spec.md`/`tasks.md` skeletons, `es/` and `en/` |

## Rules

- Do NOT write code, open a PR, or run any git write command.
- Do NOT invent what a missing or unreadable attachment probably contains.
- Do NOT decide contract-break, scope, doc-update timing, or validation-blocking on the user's
  behalf — surface each with evidence and stop.
- Do NOT default the output destination — ask, every time, even when only one tracker is detected.
- Do NOT ask more than 4 questions in one `AskUserQuestion` call, and never ask what Phase 1
  already answered.
- Do NOT assume a document's language for the spec/tasks files — match `AGENTS.md`/`docs/`.
- Do NOT commit.
