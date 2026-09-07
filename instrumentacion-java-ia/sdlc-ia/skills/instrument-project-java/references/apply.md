# Applying the eight controls

> Called from **Phase 3** of `SKILL.md`, after the scope questions are answered.

Install **in this order** — each control builds on the previous one. Read every template in
`templates/` before writing: each opens with a header of placeholders and decisions; follow it and
delete the header before writing the target file. Run each control's command once and confirm it
passes before moving on — a broken control compounds.

## 1 — Reproducible inputs

Verify, don't install, when the wrapper already exists: `mvnw`/`mvnw.cmd` present, `distributionUrl`
in `.mvn/wrapper/maven-wrapper.properties` is a literal version (never a range), no `<dependency>`
carries an inline `<version>` outside `<dependencyManagement>`/a BOM import. Missing wrapper →
`mvn wrapper:wrapper`. Loose versions → migrate into `<properties>` + `<dependencyManagement>`.

## 2 — Strict build

Declare (or extend) `maven-compiler-plugin`'s `<configuration>` with
`<compilerArgs><arg>-Xlint:all</arg><arg>-Werror</arg></compilerArgs>`. Under
`spring-boot-starter-parent` the plugin is already version-managed — add only `<configuration>`,
never a `<version>`.

## 3 — Style

A generic `.editorconfig`, plus `spotless-maven-plugin` (formatter from scope question 2,
`ratchetFrom` if question 3 chose it) bound to `verify`, plus Checkstyle (`templates/checkstyle.xml`,
`<configLocation>`) for what Spotless does not cover — unused/star imports, naming, line length. If
SonarQube is added later on a Spanish-commented repo, flag rule `S1135` ("TODO comment") first: the
token also matches ordinary Spanish words (`todo`, `todos`), producing false positives unrelated to
real markers — no template ships for this, it is a heads-up.

## 4 — Entry point

Patch, never replace, an existing `Makefile` — see `templates/Makefile.patch.md` for the exact diff
against this repo's own file. New targets: `format`, `lint`, `secrets`, `check`, `ci`, `hooks`. Keep
`.PHONY` and the `## comment` convention `make help` already parses.

## 5 — Shift-left

`templates/lefthook.yml.template`. Pre-commit checks staged-file style and scans staged secrets
(Maven/Spotless has no per-file apply equivalent to `dotnet format --include`, so it checks and
points at `make format` rather than rewriting files silently); pre-push runs `make check`. No
`commit-msg` block by default — see scope question 6.

## 6 — Secrets

`templates/.gitleaks.toml`, adapted. Pre-commit: `gitleaks protect --staged`. CI: `gitleaks detect`.
Create an empty `.gitleaksignore` with an explanatory comment. **Before wiring the gate**, run
`gitleaks detect` once against the working tree and report existing findings — otherwise a repo
with pre-existing hits blocks every commit from day one.

## 7 — Architecture tests

Follow `references/architecture-discovery.md` to derive candidate rules and `references/arch-tests.md`
to build or read the test class. **Two cases:**

- **(a) No ArchUnit yet** — install `archunit-junit5`, clone the repo's own test pattern, evaluate
  every candidate rule against the current code before writing it (passes → write; 1–2 violations →
  ask; fails broadly → report as a finding, not a test).
- **(b) ArchUnit already wired** (common on a Spring Modulith repo) — this is **discovery**, not
  installation: confirm the existing rules still pass, report what they leave open
  (`arch-tests.md`'s worked example: an `allowEmptyShould(true)` rule whose trigger condition
  already holds, and a module named in no rule at all — surface, don't silently fix).

## 8 — CI

`templates/ci/github-actions.yml.template` (primary), `templates/ci/azure-pipelines.yml.template`
(secondary). Resolve and pin gitleaks once
(`gh release view --repo gitleaks/gitleaks --json tagName --jq '.tagName'`), verify SHA256 against
`checksums.txt` before extracting, install to `$HOME/.local/bin` without `sudo` — never re-resolve
`releases/latest` per run. The workflow calls `make ci`, nothing else. Every push, every branch,
`concurrency`+`cancel-in-progress` on GitHub Actions (Azure Pipelines has no equivalent — say so).
Azure Repos PR triggers live in branch policy, not YAML; a `pr:` block there is silently ignored.
