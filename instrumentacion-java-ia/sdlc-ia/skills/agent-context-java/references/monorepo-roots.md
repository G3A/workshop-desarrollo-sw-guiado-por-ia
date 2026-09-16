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

**One line is the exception**, and only one: `REVIEW.md`'s title, when it still names a project and
the file has come to cover another. See "When a second Java project arrives" below for the trigger,
the signature check that keeps this away from a file the team wrote, and why a title earns an
exception that no item does.

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

**Pre-existing generic items are a finding**, and reporting one is not fixing it. When you append a
project's items to a `REVIEW.md` written when there was only one project, the old items say things
like "the version the `pom.xml` pins" — now ambiguous. Never rewrite them silently, and never leave
the file half-scoped, which is worse than not scoping at all. What to do instead, with its trigger
and its guards: **"When a second Java project arrives"**, below.

## When a second Java project arrives

The `REVIEW.md` at the repository root was written when there was one project. Everything here is
about the **merge**, and none of it fires unless the trigger says so.

**The trigger — all three at once:** Phase 1a's prefix is non-empty (this project is a subfolder),
`REVIEW.md` already exists at the repository root, **and** its preamble does not name this run's
project folder. Without the third condition, a second run over the *same* project — regenerating
docs after a refactor — adds the scope line and claims two pieces where there is one.

**The signature, checked before touching anything.** Treat the file as this skill's only if it
carries the template's shape: the six numbered categories plus the closing "how this list evolves"
section. If it does not, the file is the team's — report it and change nothing in it. The title
repair below is the only line this skill ever rewrites, and rewriting a line of a file the team wrote
by hand is exactly what the augment rule exists to prevent.

**The title.** A `REVIEW.md` this skill wrote before the template started naming the repository is
titled with a *project*. Rewrite that one line to the repository's name, and say so in the report: the
single exception to "augment mode never rewrites". It earns the exception because a title is not team
content — it is a label that is now false about a file covering more than it names. Nothing else is
rewritten. A title that already names the repository is left alone, and so is one where project and
repository share a name: there is nothing to change.

**The old generic items.** Add the template's **second conditional preamble sentence**, once,
declaring that items which do not name a folder were written for the folder the file already covered
— and report the ambiguous items so a person scopes the ones that matter. That is one line instead of
fifteen rewrites of the team's text. Two guards: check the sentence is not already there before
adding it (three projects would otherwise leave three near-identical sentences), and keep it phrased
as an assumption, because if anyone added items by hand the sentence covers those too without being
true. The report says that, rather than letting the file assert it.

**The organization does not change.** The folder goes inside the item's own text, as above; no
per-project sections, and no prefix or tag. A second project does **not** bring its own list of
fifteen — it adds only the items genuinely its own, for the reason `SKILL.md` already gives under
"Tailor, do not pad". Per-project sections were considered and rejected: with three or four projects
they read better, and they multiply a list whose whole value is that people reach the end of it. If
the new project's items would push the file past ~15, report it so the team prunes, instead of
growing the file in silence.

## Cross-root links

`AGENTS.md` lives in the project and lists every doc. Its link to `REVIEW.md` must climb out of the
project: one `../` per segment of the `--show-prefix` value (`base-conocimiento/` → `../REVIEW.md`).
**The PR template is the exception, and it is not a path problem but a context one.** Its link to
`REVIEW.md` must be an **absolute URL** — `https://github.com/<slug>/blob/<integration-branch>/REVIEW.md`,
with both values from Phase 1a:

- **The slug** is `gh repo view`'s `nameWithOwner`, taken whole. Do not parse it out of
  `git remote get-url origin`, which returns SSH, HTTPS and `.git`-suffixed shapes — three regexes,
  written twice because PowerShell and bash disagree, to recover a value one flag already gives.
- **`origin` only**, and no guessing between remotes. A fork has an `upstream`; this monorepo has a
  mirror repository. A link built from the wrong remote points at a parallel repo whose `REVIEW.md`
  may differ or not exist, and it still looks like a working link.
- **The integration branch before the default branch**, because a repo that integrates on `dev` and
  releases to `main` is the common case, and the criteria a reviewer needs are the ones on the branch
  the PR targets — not the ones last released.

As a file in `.github/`, `../REVIEW.md` resolves perfectly; in the
**rendered body of a PR**, which is the only place anyone clicks it, it 404s. GitHub copies the
template verbatim into the PR body and rewrites no relative paths, so the browser resolves it against
the PR's own URL (`/owner/repo/pull/123/../REVIEW.md`).

**Verify every link from the context where it is read**, not from the working directory. Two contexts,
two checks:

| Link | Read as | Verified from |
|---|---|---|
| `AGENTS.md` → `REVIEW.md` | a file, by the agent | the directory of the file that contains it |
| PR template → `REVIEW.md` | a URL, in a rendered PR body | the string itself, against Phase 1a's values |

This is not a hypothetical, and the Phase 6 check has now missed a bug for each reason: resolving
`../REVIEW.md` from the wrong starting point found a `REVIEW.md` no reader would ever load, and
resolving the PR template's link from `.github/` reported green over a link that 404s for every
reviewer. Checking a link from where it happens to live, rather than from where it is used, is the
shape of both failures.

## The orphan from an earlier run

A repository whose project ran an older version of this skill has a `<project>/REVIEW.md` that is
inert. Find it (Phase 1b), **name it in the report as an orphan with the one-line reason — nobody
loads it** — and propose the move. Do not run `git mv` yourself: writing new files is authorized,
moving a file the user may have edited is not. If the repository root already has a `REVIEW.md`,
it is not a move at all but a merge of its items as a scoped section, and that one is a question.
