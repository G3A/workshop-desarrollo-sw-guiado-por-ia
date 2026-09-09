# Applying the nine controls

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
against this repo's own file. New targets: `format`, `lint`, `secrets`, `sca`, `check`, `ci`, `hooks`. Keep
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

`templates/ci/github-actions.yml.template` → `.github/workflows/ci.yml`. GitHub Actions is the only
platform this skill writes (scope question 4). Resolve and pin gitleaks once
(`gh release view --repo gitleaks/gitleaks --json tagName --jq .tagName` prints `v8.30.1`; write
`8.30.1`, **without** the `v` — the template's URL adds it and the archive name has none), verify SHA256 against
`checksums.txt` before extracting, install to `$HOME/.local/bin` without `sudo` — never re-resolve
`releases/latest` per run. The workflow calls `make ci`, nothing else. Every push, every branch,
`concurrency`+`cancel-in-progress` so a second push cancels the first.

## 9 — Dependency vulnerabilities (SCA)

Only if scope question 7 said yes. Two halves, and they do different jobs: **OWASP
Dependency-Check** is the gate (fails `make ci` on a known CVE at or above the threshold);
**Dependabot** is the remedy (opens the upgrade PR). Neither replaces the other.

**Gate.** Declare `org.owasp:dependency-check-maven` under `<build><plugins>` in the root `pom.xml`.
Resolve the version once, at install time, from the source of truth, then pin it:

```powershell
([xml](Invoke-WebRequest -UseBasicParsing 'https://repo1.maven.org/maven2/org/owasp/dependency-check-maven/maven-metadata.xml').Content).metadata.versioning.latest
```

```bash
curl -fsSL https://repo1.maven.org/maven2/org/owasp/dependency-check-maven/maven-metadata.xml | grep -o '<latest>[^<]*' | cut -d'>' -f2
```

```xml
<plugin>
  <groupId>org.owasp</groupId>
  <artifactId>dependency-check-maven</artifactId>
  <version><!-- resolved above --></version>
  <configuration>
    <!-- 7 = High and above fails the build. The plugin's own default is 11: report only, never fails. -->
    <failBuildOnCVSS>7</failBuildOnCVSS>
    <!-- Read the NVD key from the environment; never write the key in the POM. Unset = throttled, still runs. -->
    <nvdApiKeyEnvironmentVariable>NVD_API_KEY</nvdApiKeyEnvironmentVariable>
    <!-- Outside ~/.m2 on purpose: setup-java's Maven cache is keyed on pom.xml and must not carry the NVD mirror. -->
    <dataDirectory>${user.home}/.cache/dependency-check</dataDirectory>
    <suppressionFiles>
      <suppressionFile>dependency-check-suppressions.xml</suppressionFile>
    </suppressionFiles>
    <formats>
      <format>HTML</format>
      <format>JSON</format>
    </formats>
  </configuration>
</plugin>
```

No `<executions>` block: the `check` goal binds to `verify` by default, which would put a
20-minute first run and a network dependency inside `./mvnw verify` and every pre-push hook.
`make sca` invokes it explicitly (`./mvnw -q dependency-check:check`) and `make ci` chains it —
the same "not in `check`, yes in `ci`" split control 6 uses for `secrets`. `skipTestScope` stays
at its default (`true`): a test-only dependency does not ship. `Report only` in scope question 7
means `failBuildOnCVSS` at `11` — write it explicitly with a comment, so nobody mistakes the
default for a gate.

Copy `templates/dependency-check-suppressions.xml.template` to the repo root, header removed.
**Before wiring the gate**, run `make sca` once and report existing findings for triage — a tree
that already carries a High CVE blocks every `make ci` from day one otherwise. A genuine false
positive or an accepted risk is silenced by a `<suppress>` entry carrying its `<cve>` and a
`<notes>` reason, never by lowering `failBuildOnCVSS`.

**Remedy.** On GitHub, `templates/dependabot.yml.template` → `.github/dependabot.yml`: weekly
version updates for `maven` and for `github-actions` (the pinned action majors of control 8 are
dependencies too). Nothing else is needed — no secret, no setting — but the PRs it opens still go
through the same Ruleset as everyone else's.

**CI.** `templates/ci/github-actions.yml.template` already carries the two lines this control
needs: an `actions/cache` step for the NVD mirror and `NVD_API_KEY: ${{ secrets.NVD_API_KEY }}` on
the `make ci` step. Tell the user to create that repository secret; without it CI still passes,
just slower.
