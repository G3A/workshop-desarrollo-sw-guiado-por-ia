# Hook regression suite

```bash
bash tests/run.sh              # summary
VERBOSE=1 bash tests/run.sh    # every case
```

No dependencies beyond `bash`, `sed`, `awk` and `git` — no Maven, no JDK, no network.

## Why it exists

A hook's failure mode is silence. A guard with a broken pattern **exits 0** and is
indistinguishable from a guard that looked and found nothing, so nobody discovers it until the day
it was supposed to stop something.

| Bug | What it did | Visible by reading the code? |
|---|---|---|
| `set -o pipefail` | A short-circuiting filter (`grep -q`, `head -1`) raises SIGPIPE, the pipeline reports failure, and the guard falls through to **allow** | No |
| Backslashes stripped before JSON escapes were translated | `cat .env\necho done` fused into the single token `.envnecho`, which no anchored pattern matches — and multi-line Bash is the normal case, not an exotic one | No |
| `[;\|&\n]` in `sed` | POSIX reads that bracket expression as including the letter **n**, so every `n` became a separator and `feature/my-branch`, `pom.xml` and `never run` all shattered | No |

Each is now a case. Reintroduce any of them and the suite goes red.

## What it covers

The five hooks that need nothing installed:

| Hook | Fixture |
|---|---|
| `secret-read-guard` | none — payload only |
| `block-dangerous-bash` | none — payload only |
| `generated-files-guard` | two synthetic Flyway-shaped directories |
| `version-pin-guard` | four `pom.xml` files, hand-written |
| `audit-log` | an empty git repo to write into |

`format-on-edit` and `dependency-sweep` need a Maven toolchain and a reactor that resolves.
Testing them here would make this repository depend on Maven, so they stay where they are already
covered: the skill's **Phase 5**, which fires every installed hook against a real repository.

`script-hygiene.sh` asserts what behaviour cannot show — that no delivered script enables
`pipefail`, that each one drains stdin, that `_lib.sh` keeps its documentation below the cut
marker.

## Scope

It tests the **templates in this repository**. A team that installs the skill verifies their own
**installation**, in Phase 5, against their own repo. Two different things, and this suite is for
whoever maintains the skill.

## The data is synthetic

JSON strings, nothing more. No credential, no path from anyone's machine, no network call.

One consequence worth knowing: the case files **contain** the literal strings `.env`, `id_rsa` and
`secrets.json`, because that is what they test. If this repository's own hooks are installed here,
the secret read-guard will refuse to let an agent read them. That is the guard working.

## Adding a case

```bash
check <hook-script> <deny|warn|silent|output> '<label>' "$(payload_bash 'the command')"
check <hook-script> <deny|warn|silent|output> '<label>' "$(payload_file Read /path)"
```

Every case also asserts the hook exited 0. A hook that fails a session is a bug regardless of what
it decided.

**Add the true negative too.** A guard that denies everything passes a one-sided test. Each
blocking case in this suite has an allow beside it.
