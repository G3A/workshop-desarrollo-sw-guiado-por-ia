# The hook catalogue (continued)

> Continues `references/hook-catalog.md` — same conventions (`_lib.sh`, bash 3.2, no `jq`,
> `json_path` for Windows paths, `set -u` never `set -o pipefail`). Called from **Phase 3** and
> **Phase 4** of `SKILL.md`.

## Hook 4 — Dependency sweep

`SessionStart`, matcher `startup|resume`. Reports only.

Runs `{{SWEEP_COMMAND}}` and puts the findings in the agent's context before the first prompt.
Resolution order, decided in Phase 1: a `make audit` target if the repo's `Makefile` has one;
otherwise `mvn versions:display-dependency-updates` (always available, lists dependencies with a
newer release); and if `org.owasp:dependency-check-maven` is **already** configured as a build
plugin in the pom, prefer `mvn org.owasp:dependency-check-maven:check` instead — it reports actual
published CVEs rather than only "a newer version exists."

**Why the findings grep looks for both shapes.** `versions:display-dependency-updates` prints
`old -> new` version arrows; `dependency-check:check` prints CVE ids and severity words. The
script's `FINDINGS` filter matches either (`-> `, `CVE-`, or `Critical|High|Medium|Low`,
case-insensitive) so the same hook body works with whichever command Phase 1 resolved, without a
second placeholder for the pattern.

**Why it never fails.** An advisory published overnight is not a reason for the repo to stop
working in the morning. It reports; the human decides.

**Why silence on a clean repo.** Both commands can exit 0 with nothing actionable to say. A hook
that prints "all clear" at every session start trains people to skip its output.

**Do not install `dependency-check:check` here if it is not already configured.** Its first run
downloads and builds a local CVE database — minutes, not seconds — which is exactly the "slow
`SessionStart` looks like a broken tool" failure this hook exists to avoid. Offer it only when
Phase 1 finds it already wired into the pom (meaning the database is already warm in CI or on the
machine); otherwise default to `versions:display-dependency-updates`, which is fast.

## Hook 5 — Audit log

`PreToolUse`, no matcher, `async: true`. Does not block.

Appends one tab-separated line per tool request to `logs/audit.log`: timestamp, session id, event,
tool name, and the **full `tool_input`**. Verbatim in mechanism from the .NET sibling — this hook
has nothing stack-specific in it.

**Two mandatory consequences.** `logs/` goes into `.gitignore` **before** this script is written,
or the next `git add -A` publishes it. And the Phase 6 report says out loud what the file holds —
potentially anything sensitive that passed through any tool, including a credential hook 1 missed.

**Why `async`.** A log write must never sit between the agent and its tool call.

## Hook 6 — Version-pin guard

`PostToolUse`, matcher `Edit|Write|MultiEdit`. Warns.

**Only offered when the repository already centralises dependency versions** — a
`<dependencyManagement>` block, a parent BOM import, or both — the Maven equivalent of the .NET
sibling's `Directory.Packages.props` precondition. `base-conocimiento/pom.xml` in this monorepo is
the worked example: every version is a `${property}`, resolved once in `<properties>` and imported
through `<dependencyManagement>`. Without that discipline already in place, a literal `<version>`
on a `<dependency>` is the normal, correct way to declare one, and this hook would fire on every
`mvn dependency:get`-style addition — the fastest way to get the whole catalogue switched off.

**What it does.** After a write to a `pom.xml`, strips the (single, greedy-matched)
`<dependencyManagement>` block — versions declared there are the source of truth and are exempt —
then extracts each remaining `<dependency>…</dependency>` entry (these do not nest, so no depth
counting is needed, unlike the JSON `tool_input` extraction in `audit-log.sh`) and flags any whose
`<version>` child is a literal string rather than a `${…}` property reference.

**Why it reads the file, not the payload.** An `Edit` delivers a diff, not the result.

**Known simplification, stated on purpose.** The `<dependencyManagement>` strip assumes exactly
one such block, which is the normal shape of a `pom.xml`. A file with two would need a real XML
parser rather than a single greedy `sed` pass; that trade-off is deliberate, matching the rest of
this catalogue's "portable bash over a real parser" stance.

**Why it warns instead of blocking.** `PostToolUse` runs after the write. The fix is one attribute
move, on the agent's next turn once it is told.

## Hook 7 — Generated-file guard

`PreToolUse`, matcher `Edit|Write|MultiEdit`. **Blocks — conditionally.**

Two independent branches, each installed only when its artifact exists in the repository.

**Flyway** — `src/main/resources/db/migration/` (this monorepo's `base-conocimiento` has four real
migrations, `V1` through `V4`, at exactly that path). **Unlike the .NET sibling's `Migrations/`
guard, this branch does not block every write** — Flyway migrations are normally hand-written SQL,
and creating the *next* one (`V5__add_index.sql`) is the correct, expected workflow. It blocks only
an edit to a file that **already exists** on disk: once Flyway has applied a migration it
checksums it, and a hand-edit of an applied script fails validation on the next deploy with an
error that points at a checksum, not at the edit. The denial names the fix: add a new versioned
script instead of editing the old one.

**Liquibase**, when a changelog directory is present (commonly `src/main/resources/db/changelog/`)
— installed **only if Phase 1 actually finds one**; this monorepo does not use Liquibase today.
Same existence check, same reasoning: a changelog entry is checksummed once run.

**Path matching is segment-anchored**, so `docs/migrations-guide.md` and a class named
`MigrationHelper.java` are not caught, and a Windows path is normalised before matching.

**Neither branch depends on the deterministic instrumentation having run.** It depends only on the
directory existing — a Phase 1 question, nothing more.
