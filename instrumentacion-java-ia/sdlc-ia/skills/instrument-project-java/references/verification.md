# Verify by breaking

> Called from **Phase 4** of `SKILL.md`, and referenced by name from every control in Phase 3.

Installing a gate proves nothing. Making it fail proves it is wired. **A gate nobody saw fail is
not a gate.**

Run this for every control installed in this session. Introduce the violation, confirm the gate
catches it, revert. Capture the real output — you report it in Phase 5.

**The clean-tree check belongs to Phase 3, not here.** Before writing the first file, confirm the
working tree is clean (`git status`); if it is not, stop and tell the user. By the time you reach
this phase the tree is dirty *by design* — the skill does not commit.

**The revert here is never `git checkout` on a file the install just wrote.**
`git checkout -- lefthook.yml` either fails (untracked) or restores whatever was there **before**
the install, silently undoing your own work. `git checkout` is safe for exactly one case: a
**pre-existing, committed source file** you edited to trigger a break (controls 2, 3, 7).

---

## Procedure

1. Note the exact file and the exact edit.
2. **Snapshot the file first**, outside the repo (`cp <file> <tmp>/<file>.bak`). One file, one
   break, one restore — never break two gates at once.
3. Make the edit.
4. Run the gate's command.
5. Confirm it **fails**, and that the message names the problem.
6. **Restore from the snapshot**, confirm the gate passes again.

**Two breaks reach past the working tree:**

- **Controls 5 and 6 stage their file** (`git add`). Putting the original content back leaves the
  broken version — a fake credential, for control 6 — sitting in the index. Always finish with
  `git restore --staged <file>`.
- **Control 7 may run `mvn dependency:tree` / edit `pom.xml`.** Restore the source file **and**
  `pom.xml` (and any `<properties>` entry you added), then `mvn -q dependency:tree` to confirm the
  tree is clean again.

After every break, `git status` must look exactly as it did before it.

---

## Control 1 — Reproducible inputs

**Break** (generic case only — skip if control 1 was verify-only, as in `base-conocimiento`): edit
`.mvn/wrapper/maven-wrapper.properties`, bump the version segment of `distributionUrl` to a patch
that does not exist (`apache-maven-3.9.999-bin.zip`).

```bash
./mvnw -v
```

**Expect:** the wrapper fails to download it — a 404 naming the exact URL it tried. Revert and
confirm `./mvnw -v` prints the real, pinned version again.

## Control 2 — Strict build

**Break:** add an unused import to a real, already-compiling source file.

```bash
./mvnw -q compile
```

**Expect:** the build fails with an **ERROR** (not a warning) carrying the lint category
`-Xlint:all` flags it under (`[unchecked]`, `[rawtypes]`, `[deprecation]`, …), not a silent
warning line. If it only warns, `-Werror` is not reaching that module — check for an overriding
`<configuration>` closer to the file.

## Control 3 — Style

**Break:** reorder the `import` statements in a real file (or add a trailing whitespace line, for
Checkstyle's `LineLength`/whitespace rules).

```bash
make lint
```

**Expect:** `spotless:check` and/or `checkstyle:check` fail, **naming the file**. Confirm the fix
path too: `make format` repairs what Spotless owns, `make lint` goes green again — Checkstyle
findings that Spotless does not auto-fix (e.g. `UnusedImports`) need a manual edit.

## Control 4 — Entry point

No break needed.

```bash
make help
make check
```

**Expect:** `help` lists every target with its description; `check` chains restore/format-check/
build/test in order.

## Control 5 — Shift-left

**Break:** stage a badly formatted or reordered-import file and attempt a commit.

**Prove it by the commit that did not happen, not by the message you read** — hook output can
swallow the real reason. Supply a disposable identity so a machine with no `user.name`/`user.email`
configured does not produce a false `BLOCKED`:

```bash
make hooks                                    # install the hooks first
GC='git -c user.name=instrument-check -c user.email=check@example.invalid -c commit.gpgSign=false'
HEAD_BEFORE=$(git rev-parse HEAD)
git add <file>
$GC commit -m "test: hook check"              # expected to fail
[ "$HEAD_BEFORE" = "$(git rev-parse HEAD)" ] \
  && echo "BLOCKED — no commit was written" \
  || { echo "NOT BLOCKED — undoing"; git reset --soft "$HEAD_BEFORE"; }
```

**Before trusting a `BLOCKED`, prove the commit path works at all**: run the same `$GC commit` with
hooks bypassed (`LEFTHOOK=0 $GC commit -m "test: baseline"`) on a trivial staged change and confirm
history *does* move, then `git reset --soft` it. A `BLOCKED` on a repo where nothing can commit is
the most convincing false positive here.

If it prints `NOT BLOCKED`, the reset already undid the commit — fix the hook (most often
`lefthook install` was never run: `.git/hooks` still holds only `.sample` files) and try again.

Also confirm the escape hatch and mention it in the report: `LEFTHOOK=0 git commit …`.

## Control 6 — Secrets

**Break:** stage a line matching a credential pattern. **Never `AKIAIOSFODNN7EXAMPLE`** — it is
AWS's own published example and gitleaks allowlists it by design, so the scan passing on it looks
like a broken gate when the gate is actually correct. Use a fake key that is not a documentation
sample, e.g. `AKIA4SFODNN7QWERTZXC`.

```bash
git add <file>
git -c user.name=instrument-check -c user.email=check@example.invalid -c commit.gpgSign=false \
  commit -m "test: secret check"
```

**Expect:** `gitleaks protect --staged` blocks the commit before it exists, naming the rule and the
redacted match. If it passes on your test string, run `gitleaks detect --no-banner` directly on a
file containing it to rule out an allowlist entry before suspecting anything else.

**Before wiring the gate at all**, run `gitleaks detect` once against the working tree and report
any existing findings for triage — a repo with pre-existing hits blocks every commit from day one
otherwise. Confirm the false-positive path is documented: a genuine false positive is silenced by
its **fingerprint** in `.gitleaksignore`, never by its file path (a path exclusion blinds the
scanner to everything in that file, not just the one match).

## Control 7 — Architecture tests

See `arch-tests.md` for the full procedure. Summary: add a forbidden dependency (compiles clean by
itself), then the `import` and a real use of a type from it, run `mvn test -Dtest=<TheArchTestClass>`.

**Expect:** the module **compiles**, and the arch-test fails, naming the rule and the offending
type — the compiler was happy, the repository refused anyway. Restore the source file, `pom.xml`,
and any `<properties>` entry added; confirm `mvn -q dependency:tree` is clean.

## Control 8 — CI

Cannot be broken locally. Verify by inspection instead:

- installs the JDK/Maven from the repo's own pinned source (wrapper `distributionUrl`,
  `<java.version>`), never a hardcoded literal in the workflow;
- calls `make ci` rather than restating the steps;
- triggers on **every push, on every branch** — not only the default one;
- on GitHub Actions, a second push to the same branch cancels the first (`concurrency` +
  `cancel-in-progress`) — **Azure Pipelines has no equivalent**, do not claim it there;
- a pull request inside the repository does not run the whole pipeline twice;
- for Azure Repos, there is no `pr:` block — PR validation comes from the branch policy, and the
  YAML file alone does nothing until it is wired through the Pipelines UI **and** added to a branch
  policy. Report control 8 as **written but not yet active** for Azure DevOps.

---

## Closing

```bash
git status          # only the files the install produced — nothing else added or modified
git diff             # confirm no break survived in a pre-existing file
make check
```

An extra modified file means a break was not reverted; a **missing** one means a `git checkout`
wiped part of the install. **Do not report success while any gate is red.**
