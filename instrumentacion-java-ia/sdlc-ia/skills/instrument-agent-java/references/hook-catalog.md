# The hook catalogue

> Called from **Phase 3** and **Phase 4** of `SKILL.md`.

Seven hooks. Each entry says what it does, why it earns its cost, what it deliberately does not
cover, and what it costs to get wrong. **1 and 2 are the default. 3 to 7 are offered.**

---

## What every script here has in common

They share `_lib.sh` and are all written to three constraints, none of them stylistic:

- **bash 3.2** — no associative arrays, no `mapfile`.
- **no `jq`** — absent from a default macOS and a default Windows. Field extraction is awk plus
  sed, and it handles exactly the flat string fields these hooks need.
- **Git Bash on Windows** — `tool_input.file_path` arrives with **backslashes**. `json_path`
  normalises them; `json_raw` does not. Anything path-shaped goes through `json_path`.

And the rule that is easy to get wrong and impossible to notice:

> **`set -u`, never `set -o pipefail`.** With `pipefail`, a filter that short-circuits (`grep -q`,
> `head -1`) raises SIGPIPE, the pipeline reports failure, and the guard falls through to
> **allow**. It exits 0. Nothing is blocked, nothing is logged, and it looks exactly like a guard
> that found nothing.
>
> **Normalise before you split.** A multi-line Bash command arrives as the two characters `\` and
> `n`, not a newline. Strip backslashes first and `cat .env\necho done` fuses into the single
> token `.envnecho`, which no anchored pattern matches — and Claude Code emits multi-line Bash
> constantly, so this is the normal case. Convert `\n`/`\t`/`\r` to separators **before** removing
> quoting.
>
> **Two silent `sed` traps.** Inside a bracket expression, POSIX reads `[;|&\n]` as the characters
> `;` `|` `&` `\` and the **letter n** — every `n` becomes a separator. And BSD `sed` does not
> expand `\n` in the *replacement* either. Normalise to a plain character with `sed`, let `tr`
> make the newlines.

## ⭐ Hook 1 — Secret read-guard

`PreToolUse`, matcher `Bash|Read`. **Blocks.**

Reads the target out of the tool call — `file_path` for `Read`, the tokenised `command` for
`Bash` — and denies when it names a credential file: `.env`, `*.pem/pfx/p12/jks/keystore`,
`id_rsa`/`id_ed25519`, `secrets.json`, `application-secrets*.yml`, `.ssh/`, `.aws/credentials`.

**Why field-based, not a grep over the payload.** The obvious version greps raw stdin for `.env`
and also denies `git status` and `cat .gitignore`. Match the field, tokenise the Bash command —
whole-string matching cannot tell `cat .env` from `cat .env.example`.

**Known, intentional false positive.** `cp .env.example .env` is denied: one argument is a bare
`.env`. Creating a credential file is a step for a human.

**Matcher is `Bash|Read`, not `Bash|PowerShell|Read`.** Unlike hook 1 in the .NET sibling skill,
this catalogue does not add `PowerShell` here either — say so plainly rather than claim coverage
this skill did not verify. If the team is genuinely on native PowerShell rather than Git Bash,
widen the matcher and re-verify in Phase 5 before trusting it.

**What it does not cover.**
- **`@`-referenced files** — inlined into the prompt with no tool call, so no `PreToolUse` hook
  fires. Close that gap with a `Read` deny rule in permissions, not a hook.
- **`Grep`/`Glob`** — can surface a secret's contents. Add them to the matcher if wanted; accept
  false positives on repo-wide searches.
- **Spring's own secrets mechanisms** — `application-local.yml` outside version control, or a
  secrets manager profile. Blocking the local override file breaks legitimate work.

**Widening to writes** is one word in the matcher: add `Edit|Write|MultiEdit`.

## ⭐ Hook 2 — Format on edit

`PostToolUse`, matcher `Edit|Write|MultiEdit`. Does not block.

Runs `mvn -q -f {{POM_PATH}} spotless:apply -DspotlessFiles=<pattern>` against the file that just
changed.

**Why Spotless and `-DspotlessFiles`, not `make format`.** `spotless:apply` with no file filter
rewrites the whole module — tens of seconds, files nobody touched, on every edit. `-DspotlessFiles`
scopes it: the flag takes a comma-separated list of patterns matched with `String#matches` against
the **absolute** file path — confirmed against the plugin's own README, not assumed. This is the
one place in this catalogue where a Makefile target exists (`make format`, if the repo has one)
and is deliberately bypassed — say so in the report.

**The flag is a regex against an absolute path, and that is a trap on Windows.** Reproducing the
exact absolute path Spotless will see — drive letter, separator style — is not reliable across
JVM/Maven versions. The script sidesteps it instead of fighting it: it escapes the file's
repo-relative path, replaces every `/` with a character class matching either separator
(`[/\\]`), and prefixes `.*` so the pattern matches *any* absolute prefix ending in that relative
suffix. `String#matches` requires a full match, so `.*<escaped-relative-path>` is sufficient and
never depends on how Maven spells the drive letter.

**Why it still needs a `pom.xml`.** `-f {{POM_PATH}}` tells Maven which project to load; a
monorepo where the Java project lives in a subfolder (as here) needs that path recorded, the same
way the .NET sibling records a solution path.

**Why it never blocks.** `PostToolUse` runs after the write; there is nothing left to stop. The
common failure is boring: the file does not compile yet because the agent is mid-refactor.

**Precondition.** `spotless-maven-plugin` must already be declared in the pom (build plugin or
`pluginManagement`). Without it, `mvn spotless:apply` has no format rules to run and either fails
or silently does nothing depending on the invocation — check for the plugin in Phase 1 and say so
if it is missing, the same way the .NET sibling requires `.editorconfig` before it offers hook 2.

**Only `.java` files trigger it.** Calling Maven for a `.md` or `.xml` edit pays the JVM+reactor
startup cost — commonly 1-3 seconds — for a formatter with nothing to say about that file type.

## Hook 3 — Dangerous-command blocker

`PreToolUse`, matcher `Bash`. **Blocks.**

**Never `Bash|PowerShell`.** Everything below parses shell syntax — splitting on `;`/`&&`/`|`,
dropping `VAR=value` prefixes, dispatching on the command word — and none of it describes
PowerShell. `Remove-Item -Recurse -Force C:\` would reach the end of the loop and exit 0: coverage
claimed, nothing checked. This is the exact bug the .NET sibling shipped and later fixed by
narrowing the matcher to `Bash` alone — do not reintroduce it here.

**Six patterns, each unrecoverable or outward-facing:**

| Pattern | Why it is on the list |
|---|---|
| `rm -rf` on a target outside the repository | No undo, no trash. Asks one question — is the target outside the working tree — instead of enumerating system paths, which always misses one (`/usr/local`, `/etc` are no less final than `/`) |
| `sudo` / `doas` | Nothing in a coding task needs root |
| force-push to a protected branch, **or to none** | `git push -f` and `git push --force origin HEAD` name no destination and push whatever is checked out, which may be the protected branch |
| `git reset --hard` | Discards uncommitted work with no reflog entry for the working tree |
| `mvn deploy` / `mvn release:perform` | Publishes to a remote repository. Not truly deletable once other builds have resolved it — the Maven analogue of `dotnet nuget push` |
| `rm` of `.mvn/wrapper/maven-wrapper.properties` or the root `pom.xml` | Silently undoes reproducibility (the wrapper pin) or removes the module's build definition entirely |

**Why command words, not a grep over the whole string.** `grep 'git reset .*--hard'` also fires on
`echo "never run git reset --hard"` and on the `README.md` heredoc this very skill's Phase 6
writes. Each rule matches the **command word**, never text sitting in an argument.

**The root-`pom.xml` check is a heuristic, not a guarantee.** A bare `rm pom.xml` is flagged
regardless of which module's directory the agent is notionally "in" — this catalogue cannot see
the shell's cwd, only the command text — and `rm $ROOT/pom.xml` (the resolved absolute path) is
flagged precisely. A relative multi-segment path to the root POM from an unrelated cwd can be
missed; that is the same class of imprecision the .NET sibling accepts for `global.json`. State it
in the report rather than promising exhaustive coverage.

**Not a security boundary.** `eval $(echo cm0gLXJm | base64 -d)` walks straight past — this is text
analysis of a known-shape mistake, not a shell parser. Say so in the report.

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
