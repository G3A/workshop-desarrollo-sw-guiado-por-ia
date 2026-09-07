# The hook catalogue

> Called from **Phase 3** and **Phase 4** of `SKILL.md`.

Seven hooks. Each entry says what it does, why it earns its cost, what it deliberately does not
cover, and what it costs to get wrong. **1 and 2 are the default. 3 to 7 are offered.**

Phase 4's template → output mapping, into `scripts/agent-hooks/`:

| Template | Becomes | Only when |
|---|---|---|
| `templates/hooks/_lib.sh.template` | `scripts/agent-hooks/_lib.sh` | always |
| `templates/hooks/secret-read-guard.sh.template` | `…/secret-read-guard.sh` | hook 1 |
| `templates/hooks/format-on-edit.sh.template` | `…/format-on-edit.sh` | hook 2 |
| `templates/hooks/block-dangerous-bash.sh.template` | `…/block-dangerous-bash.sh` | hook 3 |
| `templates/hooks/dependency-sweep.sh.template` | `…/dependency-sweep.sh` | hook 4 |
| `templates/hooks/audit-log.sh.template` | `…/audit-log.sh` | hook 5 |
| `templates/hooks/version-pin-guard.sh.template` | `…/version-pin-guard.sh` | hook 6 |
| `templates/hooks/generated-files-guard.sh.template` | `…/generated-files-guard.sh` | hook 7 |

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

`PreToolUse`, matcher `Bash|PowerShell|Read`. **Blocks.**

Reads the target out of the tool call — `file_path` for `Read`, the tokenised `command` for
`Bash` and, separately, for `PowerShell` — and denies when it names a credential file: `.env`,
`*.pem/pfx/p12/jks/keystore`, `id_rsa`/`id_ed25519`, `secrets.json`, `application-secrets*.yml`,
`.ssh/`, `.aws/credentials`.

**Why field-based, not a grep over the payload.** The obvious version greps raw stdin for `.env`
and also denies `git status` and `cat .gitignore`. Match the field, tokenise the command —
whole-string matching cannot tell `cat .env` from `cat .env.example`.

**Known, intentional false positive.** `cp .env.example .env` (or, under PowerShell,
`Copy-Item .env.example .env`) is denied: one argument is a bare `.env`. Creating a credential
file is a step for a human.

**Matcher is `Bash|PowerShell|Read`, and both shells get their own tokeniser — PowerShell is not
routed through the Bash branch.** This is safe where hook 3 (`block-dangerous-bash`) deliberately
stays `Bash`-only: hook 3 has to know *which* argument is the target across many flag
combinations, real control-flow semantics that differ per shell. This hook only asks whether a
credential-shaped token appears anywhere in the command, a question that does not depend on
control flow — so a dedicated PowerShell tokeniser answers it correctly without needing
PowerShell's full grammar. Still confirm it fires through a real `PowerShell` tool call in Phase 5
— do not assume the Bash trigger proves both branches.

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
narrowing the matcher to `Bash` alone — do not reintroduce it here. Hook 1 above covers
`PowerShell` too, but for a different reason: it only has to spot a token, not parse control flow.
A genuine PowerShell equivalent of this hook is its own script with its own tokeniser, not this
one with a wider matcher.

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

**Hooks 4–7 continue in `references/hook-catalog-2.md`.**
