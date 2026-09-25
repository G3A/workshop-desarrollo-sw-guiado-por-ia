# Preflight — does the ground actually run?

Phase 2. Still diagnosis: nothing versionable is written. Build output (`target/`, `node_modules/`,
a downloaded image) is expected and fine.

A plan drawn over an environment that doesn't compile produces layers nobody can deliver. The
failure mode is specific and it costs a whole layer: promise the front-end acceptance layer, then
discover at writing time that the corporate npm feed doesn't serve `angular-mocks`. Seconds here,
a layer there.

Run these five in order — each one can invalidate the next.

## 1. The runtime the build demands — before compiling anything

The machine's default runtime is not necessarily the one this repo builds under, and finding out by
compiling wastes the compile. Read what the build declares (`source`/`target` and plugin versions
in `pom.xml`, `engines` in `package.json`, `python_requires`, the toolchain file), then locate a
matching runtime already installed and pin it for the whole session (`JAVA_HOME`, `nvm use`, the
virtualenv).

A legacy `pom.xml` with `maven-compiler-plugin` 2.x or `aspectj-maven-plugin` 1.x does not build
under a current JDK — that is a pinning problem, not a code problem, and "upgrade the plugin" is a
production change this skill does not make.

Record the pinned runtime as an **execution convention** in the Phase 5 ADR. Without it, the green
you get locally is green nobody else can reproduce.

## 2. A compilable baseline

`mvn -q compile`, `npm ci`, `pip install -e .` — whatever the stack's equivalent is, run it with the
runtime from step 1 and confirm it's clean. If the baseline doesn't compile, stop and report: no
layer is generatable until it does, and the fix is almost certainly a production change the user has
to approve first.

## 3. The existing suite, and its baseline of failures

If tests exist, run them and **record the identity and count of what already fails**. A suite that
was already red is a **prior finding**, reported as a baseline — not damage this run caused, and not
something to fix in passing.

This number is what Phase 7's batch criterion compares against: "the batch is green" means *zero new
failures relative to this baseline*, never "zero failures". Without the baseline recorded, the first
red batch is unattributable and the run stalls on somebody else's bug.

## 4. Container runtime available?

Check whether Docker (or Podman, or whatever the repo's compose files assume) is present and can
actually start a container — not just that the CLI is on PATH. This determines whether two layers
are even candidates:

- **Contract tests' real side** (`Real*ContractIT` and equivalents) — needs the real collaborator.
- **Acceptance tests** — needs the SUT standing up with real infrastructure.

Without it, both are offered in Phase 4 already marked blocked, and the coverage yardstick in
Phase 7 changes accordingly.

## 5. Does the dependency feed resolve the test dependencies the layers will need?

Corporate mirrors and locked-down registries serve runtime dependencies and quietly lack test ones.
Probe the keys for the detected stack before promising a layer that needs them:
`mvn dependency:get` / `npm view` / `pip index versions` against the specific artifacts —
JUnit, the mocking library, the assertion library, the property-based testing library,
`angular-mocks`, Karma, Testcontainers, k6.

Gotcha worth knowing: if the `pom.xml` pins an old `maven-dependency-plugin` (2.x), a bare
`dependency:get` fails asking for `repositoryUrl`. Invoke the qualified goal instead —
`org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get`.

## What the result does

The preflight **conditions the Phase 4 menu**. A layer whose prerequisite failed is offered already
marked **"blocked by environment (`<cause>`)"** — it can be chosen as a proposed issue, never as a
layer this run promises to generate. Record every block, with its cause, in the Phase 5 ADR.
