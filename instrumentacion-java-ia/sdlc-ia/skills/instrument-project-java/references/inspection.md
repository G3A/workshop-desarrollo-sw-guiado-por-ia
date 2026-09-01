# Discovery checklist

> Called from **Phase 1** of `SKILL.md`.

Read real files. Where a fact is not readable, ask in Phase 2 — never guess. Everything here is
read-only.

---

## 1. Confirm this is a Maven (or Gradle) Java repository

Glob for `pom.xml`, `build.gradle`, `build.gradle.kts`, `settings.gradle(.kts)`, `mvnw`, `gradlew`.
If nothing matches, stop and tell the user this skill only applies to Java repositories. Write
nothing.

This skill's templates and worked examples target **Maven**. If the repo is Gradle, the eight
controls still apply but every artifact path differs (`build.gradle` instead of `pom.xml`,
`checkstyle` + `spotless` Gradle plugins instead of Maven plugins, `gradlew` instead of `mvnw`) —
say so up front and adapt each template by hand; do not silently force a Maven layout onto a
Gradle repo.

## 2. Wrapper and version pin

```bash
ls mvnw mvnw.cmd .mvn/wrapper/maven-wrapper.properties
cat .mvn/wrapper/maven-wrapper.properties
```

Record `distributionUrl`. It must be a **literal** version (`.../apache-maven-3.9.11-bin.zip`),
never a moving target. If the wrapper is absent, control 1 has to install it
(`mvn wrapper:wrapper`) rather than assume a system-wide Maven.

Worked example — `base-conocimiento`: `wrapperVersion=3.3.4`,
`distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip`.
Literal, pinned. Control 1 here is **verify-only**.

## 3. Module graph

- Single module (one `pom.xml`, no `<module>` elements) vs. multi-module (`<modules>` at the root,
  each child a `pom.xml`).
- `groupId` and root package — the namespace prefix arch-tests key off (e.g. `co.g3a`,
  package root `co.g3a.baseconocimiento`).
- For multi-module: read every child's `<dependency>` on a sibling `<artifactId>` — that graph is
  the architecture as actually built, same role as `<ProjectReference>` in a .NET solution.

## 4. Java target

Grep `<java.version>`, `<maven.compiler.release>`, `<maven.compiler.target>`, or a `<toolchain>`
block. If a parent POM sets it (e.g. `spring-boot-starter-parent`), record how the child overrides
or inherits it. Divergence across modules is a Phase 2 question, not a guess.

## 5. Spring Boot vs. plain Jakarta EE

- `<parent><artifactId>spring-boot-starter-parent</artifactId>` or any `spring-boot-starter-*`
  dependency → Spring Boot. Record its version.
- `jakarta.*` API dependencies with no Spring Boot parent, often alongside a WildFly/Payara
  deployment descriptor → plain Jakarta EE on an application server. This changes control 8 (CI
  packages a WAR, not a runnable jar) and control 7 (no Spring Modulith to lean on for control 7's
  module graph — see `architecture-discovery.md`).

## 6. Dependency management (control 1's other half)

```bash
grep -n "<dependencyManagement>" -A5 pom.xml
grep -n "<version>" pom.xml   # then manually exclude matches inside <dependencyManagement> and <parent>
```

- BOM imports (`<scope>import</scope>`, `<type>pom</type>`) inside `<dependencyManagement>` are
  the Java equivalent of .NET's Central Package Management — record every one, with its version
  property.
- Any `<dependency>` with an inline `<version>` **outside** `<dependencyManagement>` and outside a
  BOM is unmanaged — the generic (non-`base-conocimiento`) case migrates these into `<properties>`
  + `<dependencyManagement>`.
- Version drift: the same `<artifactId>` pinned to two different literals across modules.

Worked example — `base-conocimiento`: every version lives in `<properties>` (`spring-modulith.version`,
`spring-ai.version`, `archunit.version`, …), four BOM imports
(`spring-modulith-bom`, `spring-ai-bom`, `testcontainers-bom`, `arconia-bom`). Zero inline versions
outside that pattern. Control 1 is satisfied; nothing to install.

## 7. Test setup

- JUnit 5 (`junit-jupiter` / `spring-boot-starter-test` on Boot 2.2+) vs. JUnit 4.
- `spring-modulith-starter-test` present → `ApplicationModules.verify()` is available (control 7).
- Testcontainers, WireMock — informs whether `make test` needs Docker, which the Makefile and CI
  template must both assume.
- Surefire's `<includes>` pattern (e.g. `**/*Test.java`) — decides whether an architecture test
  class runs under plain `mvn test` or needs a separate profile/goal.

## 8. Existing controls

For each of the eight, record `present` / `partial` / `missing` **and what it contains**:

| Control | Look for | "Partial" looks like |
|---|---|---|
| Reproducible inputs | `mvnw`, `.mvn/wrapper/*.properties`, BOM imports | Wrapper present but `distributionUrl` uses a range, or half the dependencies are BOM-managed and half are not |
| Strict build | `<compilerArgs>` with `-Werror` on `maven-compiler-plugin` | Plugin declared with no `<compilerArgs>`, or `-Xlint` without `-Werror` (warnings visible, nothing enforced) |
| Style | `.editorconfig`, `spotless-maven-plugin`, `checkstyle.xml` | Spotless installed with no `check` goal bound to `verify` (format-only, never gates) |
| Entry point | `Makefile`, `justfile` | A Makefile with no `lint`/`check`/`ci` targets |
| Shift-left | `lefthook.yml`, `.pre-commit-config.yaml` | Config present but `.git/hooks` still all `.sample` — never installed |
| Secrets | `.gitleaks.toml`, `.gitleaksignore` | Gitleaks wired in CI only — the secret is already in history by the time it fires |
| Architecture | `archunit-junit5` dependency, an `*ArchTest.java`/`*ArchitectureTest.java` class | Test class exists with `allowEmptyShould(true)` rules whose trigger condition is already true — see `references/arch-tests.md` |
| CI | `.github/workflows/`, `.azdevops/`, `azure-pipelines.yml` | Pipeline builds but runs no gates, or duplicates Makefile steps by hand |

**A partial control is more dangerous than a missing one** — the team believes it is covered. Call
these out explicitly in the report.

## 9. CI platform

```bash
ls .github/workflows/ .azdevops/ 2>/dev/null
find . -maxdepth 2 -iname "azure-pipelines*.yml"
```

If exactly one platform is present, use it. If none or several, ask in Phase 2.

## 10. Context documents

Read, don't just detect: `AGENTS.md`/`CLAUDE.md` ("Checks to run" section, if any), `docs/architecture.md`,
`docs/adrs/`, `README.md`. A layering rule stated here and not enforced is a control-7 candidate —
see `architecture-discovery.md`.

## 11. Commit conventions

```bash
git log --oneline -20
```

Do the subjects follow Conventional Commits (`feat:`, `fix(scope):`)? This is the only evidence for
whether the `commit-msg` hook belongs in `lefthook.yml`. Imposing a convention the team does not use
is a team decision, not an instrumentation fix — if the log says no, the hook stays out and the
report says why.

Worked example — this monorepo's log is 20 descriptive Spanish subjects
(`Agrega sintesis estructurada a ocho candidatos descartados…`), no `feat:`/`fix:` prefix anywhere.
The `commit-msg` block is **not installed** by default here.

## 12. Environment facts

```bash
./mvnw -v          # or mvn -v if no wrapper yet
java -version
make --version
lefthook version
git config core.hooksPath
```

Report the JDK actually on the machine and flag any mismatch with `<java.version>`.
