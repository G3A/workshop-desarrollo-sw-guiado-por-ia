# Two roots: where each file goes when the project is not the repository

> Called from **Phase 1a**, **Phase 3**, **Phase 4** and **Phase 6** of `SKILL.md`.

A Java project is often not the whole repository. It sits in a subfolder of a monorepo, next to
other projects. "Repo root" then means two different directories, and putting a file in the wrong
one produces the worst kind of failure: the file is written, the run reports success, and nothing
reads it.

## The principle

**Anchor each file to the root of whoever reads it.** Don't memorize the table below — derive it.

| Reader | Where it looks | So the file goes to |
|---|---|---|
| The cloud Code Review service | the repository root, only | **repository root** |
| GitHub (PR template) | `.github/`, root or `docs/` **of the repository** | **repository root** |
| The team (a shared agreement) | wherever the team keeps it, once per repo | **repository root** |
| The coding agent, following links from `AGENTS.md` | every level of the hierarchy | **project root** |

Applied:

- **repository root** — `REVIEW.md`, `.github/pull_request_template.md`, `EXPERIMENTS.md`.
- **project root** — `AGENTS.md`, `CLAUDE.md`, `docs/` (including `docs/claims-ledger.md`),
  `COMPONENTS.md`.

`EXPERIMENTS.md` is on the repository side for a different reason than the other two: nothing
external reads it, but it records what the **team** may try with the agent and what happens when it
goes wrong. A risk posture per subfolder is incoherent — the same team owns all of them. Two
`EXPERIMENTS.md` in one repository is the failure to avoid.

`COMPONENTS.md` stays with the project precisely because the contrast holds: it describes *that
project's* UI, and the agent that reads it is reading that project's code.

**The evidence for the split** (<https://code.claude.com/docs/en/code-review>, read 2026-09-16):
"`REVIEW.md` is a file at your repository root"; "The review follows your `CLAUDE.md` like any
Claude Code session, but it doesn't read `REVIEW.md`"; "Claude reads `CLAUDE.md` files at every
level of your directory hierarchy". So a `REVIEW.md` in a subfolder is read by nobody at all —
not by the cloud reviewer, which only looks at the root, and not by the local `/code-review`,
which never reads `REVIEW.md` anywhere.

## Detecting it

Every query below takes **`-C <project-root>`**. They all answer about the current directory, and
the session's working directory is frequently *not* the project — Glob finds `pom.xml` in a
subfolder while the shell sits at the repository root, and the Bash tool resets the directory
between calls. Run bare, they describe the wrong place, and a subfolder project reads as "the
project is the root" — this bug inverted.

**Run the guard first, then the prefix.** Outside a work tree `--show-prefix` also prints nothing
(exiting 128), so empty output on its own cannot distinguish "no repository" from "the project is
the repository root".

```
git -C <project-root> rev-parse --is-inside-work-tree
git -C <project-root> rev-parse --show-prefix
```

Not `true` from the first → no repository; both roots collapse to the project root (see guard 1
below). Otherwise read the second: empty → the project **is** the repository root, nothing special
to do. Non-empty (`base-conocimiento/`) → the project is in a subfolder, **and that string is the
prefix** you need for cross-root links and for scoping items.

**Do not compare paths as strings.** `git rev-parse --show-toplevel` returns `D:/path/...` with
forward slashes, while the working directory is `D:\path\...` in PowerShell and `/d/path/...` in
MSYS bash. A string comparison reports "different" on every Windows repository, including the
ordinary case where the project *is* the root. `--show-prefix` sidesteps the comparison entirely,
and works the same in PowerShell 5.1, PowerShell 7 and bash.

### Three guards before writing anything outside the project

1. `git -C <project-root> rev-parse --is-inside-work-tree` — no repository at all (or a bare repo
   via `GIT_DIR`) means both roots collapse to the project directory. Say so in the report, and add
   one line: if a repository is later initialized *above* this folder, `REVIEW.md` is in the wrong
   place again. Drop the `git add`/`git commit` suggestion in that case.
2. `git -C <project-root> ls-files --error-unmatch pom.xml` — is the project actually tracked by
   that repository?
3. `git -C <project-root> check-ignore -q .` — is the project ignored by it?

**The paths in guards 2 and 3 are relative to the project**, because `-C` already put git there.
Writing `<project>/pom.xml` instead makes the guard fail with "did not match any file" in exactly
the subfolder case it exists to validate, and the skill would wrongly conclude the project is not
part of the repository.

Guards 2 and 3 catch a real and common shape: the user runs the skill in
`C:\Users\<user>\projects\myapp` while `C:\Users\<user>` is a dotfiles repository. Without them the
skill writes `REVIEW.md` and a PR template into the user's personal repo. If either guard says the
project is not part of that repository, treat it as "no repository" and say why.

A **submodule** is not this case: `--show-prefix` is empty inside it, which is correct — GitHub
reviews a submodule as its own repository.

## Writing to the repository root

**Write without asking when the root is free.** Ask once, with `AskUserQuestion`, only when:

- the repository root already has a `REVIEW.md` or a `.github/pull_request_template.md` that this
  skill did not write, or
- a `CODEOWNERS` covers the repository root.

Those two are the cases where the files belong to someone else. Everywhere else, writing two files
outside the user's folder is exactly what they invoked the skill for — but **the Phase 6 report
must name them and say which root each went to**, because with no question asked the report is the
only place the user learns it happened.

### The two root files are not symmetrical with the rest

For `REVIEW.md`, the PR template and `EXPERIMENTS.md`, the **repository root is the only
authority**. A copy of any of them inside the project folder is a finding to report, never a
destination to append to. In particular, do not let a leftover `<project>/REVIEW.md` from an older
run redirect the write.

### Augment mode: consult, don't switch

Reading these files at the repository root decides one thing only — *don't overwrite, append a
scoped section*. It must **not** count as "this project already has context". Otherwise a monorepo
whose root holds `AGENTS.md`, `CLAUDE.md` and `docs/` would put a brand-new Java subproject into
augment mode and refuse to create its `AGENTS.md` at all. Phase 1b's detection stays anchored to
the **project root**.

The Phase 2a overwrite confirmation has the same trap: a user who picks "overwrite" is thinking
about their own project's docs. Never read that answer as permission to overwrite a `REVIEW.md` or
PR template that belongs to the repository — and possibly to another project.

### One PR template per repository, ever

`REVIEW.md` items get scoped per project; the PR template does not. It has six boxes, one per
category, identical for every project. If the repository root already has one that links to
`REVIEW.md`, append nothing — a second "Human review" section with the same six boxes is pure
noise.

## Scoping items to a project

Two mechanisms, both already demonstrated in this monorepo's own `REVIEW.md`:

1. **A preamble line** saying where the file lives, why, and which pieces it covers. The template
   carries it as a conditional block — you fill in the folder names, you don't redraft the
   sentence. Real example (`REVIEW.md:6-7` of this repo):

   > Vive en la raíz del monorepo porque el servicio de Code Review solo lee `REVIEW.md` ahí;
   > aplica a las cuatro piezas. Los ítems que solo valen para `base-conocimiento/` lo dicen.

2. **The folder named inside the item's own text** — no dedicated prefix or tag. Real example
   from the same file: "*Respeta los límites de módulo que protege `ArquitecturaTest` en
   `base-conocimiento/`*". An item that applies to everything says nothing extra.

**Pre-existing generic items are a finding.** When you append a project's items to a `REVIEW.md`
written when there was only one project, the old items say things like "the version the `pom.xml`
pins" — now ambiguous. Report them as needing scoping; do not rewrite them silently and do not
leave the file half-scoped, which is worse than not scoping at all.

## Cross-root links

`AGENTS.md` lives in the project and lists every doc. Its link to `REVIEW.md` must climb out of the
project: one `../` per segment of the `--show-prefix` value (`base-conocimiento/` → `../REVIEW.md`).
The PR template's own `../REVIEW.md` needs no adjustment — it is relative to `.github/`, and with
both files at the repository root it resolves as written.

**Verify every link from the directory of the file that contains it**, not from the working
directory. This is not a hypothetical: the Phase 6 link check already existed and did not catch
this bug, because resolving `../REVIEW.md` from the wrong starting point found a `REVIEW.md` that
no reader would ever load.

## The orphan from an earlier run

A repository whose project ran an older version of this skill has a `<project>/REVIEW.md` that is
inert. Find it (Phase 1b), **name it in the report as an orphan with the one-line reason — nobody
loads it** — and propose the move. Do not run `git mv` yourself: writing new files is authorized,
moving a file the user may have edited is not. If the repository root already has a `REVIEW.md`,
it is not a move at all but a merge of its items as a scoped section, and that one is a question.
