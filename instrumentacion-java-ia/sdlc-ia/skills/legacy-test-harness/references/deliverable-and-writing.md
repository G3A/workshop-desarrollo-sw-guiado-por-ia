# The deliverable (Phase 5) and how it gets written (Phase 6)

## Phase 5 — consolidate, still in plan mode

Prepare all of this **without writing a single file**. It is what the user approves at the Phase 6
checkpoint.

### 1. The test-strategy ADR

One ADR for the repo, in the repo's own `docs/adrs/` (create it, with a README index, if absent).
It records:

- **Context, options and decision** — which layers, and why.
- **The preflight result** (Phase 2): every environment block with its cause, and the **pinned
  runtime** as an execution convention.
- **The census as an appendix** — state per actor, in the five-state vocabulary, per
  `census-and-tranche.md`. This is what the incremental mode reads on a later run; without it the
  work is neither auditable nor resumable.
- **The agreed tranche**, if there is one.
- **The inventory of doubles as a second appendix** — `| Double | Actor | Type | Note |`, one row
  per double including the ones invisible by filename. Type is one of `fake-with-contract`,
  `trivial-mock`, `pending-contract`. This is the **only** place a trivial mock's exemption from the
  contract rule is declared — never a comment inside a test.
- **The execution conventions**: the two-speed split and how to opt into the slow one, pinned
  timezone and locale, random order for the fast suite, the test layout.
- **The proposed seams**, each with its Feathers technique and its characterization net.
- **The pipeline gates** turned on, and the result of their first run.

### 2. The test tree — root-by-type layout

The first level under the test root names the **type** of test. Only `unit/` mirrors the production
package structure underneath. The tree then says at a glance which layers exist, and Phase 7 counts
them trivially:

```
<test source root>/<root package>/
├── unit/<mirror of production>/         ← collaboration tests
├── contract/                            ← abstract suites + the fake's contract test
│   └── real/                            ← the real side, gated
├── characterization/                    ← golden masters for 🟡/🔴 actors
├── acceptance/                          ← SUT base + DSL + drivers + the tests
└── testutil/                            ← injector, shared fakes/ and builders/
<test resources>/characterization/       ← recorded outputs
<test resources>/acceptance/             ← compose files, stubs, seeds
perf/  ·  security/                      ← at the repo root
```

Tests land in a package separate from production, so they enter through the **public API** (plus
reflection to inject). If a test "needs" package-private access, that is a seam to propose — not a
reason to break the layout.

**Front-end is the exception: the tooling's conventions win.** Angular/Vitest specs stay
**colocated** next to the code (`src/app/**/*.spec.ts`) — moving them to a parallel tree breaks the
tooling. Playwright e2e goes in `e2e/` at the front-end root. AngularJS follows whatever Karma
convention the repo already uses. For the gate, "production" on the front end means everything under
`src/` **except** `*.spec.ts`/`*.test.ts` and the test configuration.

### 3. Scaffolding — optional, and marked

Only if a layer ended **100% blocked** by unapproved seams **and the user explicitly accepts it at
the Phase 6 checkpoint**: one self-contained example under `_scaffolding/`, with a doc comment
saying in as many words that it is **not coverage**. That layer is then reported **blocked**, never
generated. Those two conditions together are the only rule about scaffolding — without both, it
isn't written.

## Phase 6 — write it

Only after an explicit yes. Call `ExitPlanMode`, then:

### Where writing is allowed

Test paths (`src/test/...`, `e2e/`, `perf/`, `security/`, colocated specs) and pipeline/sensor
configuration. **Never production code.**

**The build file's test surface** is the one editable part of `pom.xml` / `package.json` / the
equivalent: `test`-scoped dependencies and `devDependencies` (including *removing* a dead test
dependency — a stray `testng` with no tests hijacks the Surefire provider and makes the JUnit tests
run zero, silently), the test plugins and the opt-in profile, `argLine` for timezone and locale, and
report-only plugins (JaCoCo, PITest). Anything touching the deployable artifact — runtime
dependencies, build or packaging plugins — is **proposed as a seam**, not applied.

### Generate in batches

**Mandatory once the census has more than 5 actors.** Do not write everything and validate at the
end. Iterate in batches of 3 to 5 actors: generate → compile → run the fast suite → mini-gate over
the batch → next batch.

**"The batch is green" means zero *new* failures against the Phase 2 baseline** — compare identity
and count. Pre-existing red tests stay in the ADR as a prior finding; they are neither fixed nor
disabled in passing. With the baseline pinned, a new failure can only have come from the current
batch.

**Retry budget, per actor: 2 retries (3 attempts total).** On the third red, mark that actor
`blocked` with its diagnosis and carry on with the rest of the batch. Neither an infinite loop nor a
silent abandonment.

Batches can be delegated to subagents in large repos, each receiving its census rows, the stack's
reference, and the pinned runtime.

⚠️ **Parallel subagents only with isolated worktrees.** Two concurrent `mvn` or `npm test` runs over
the same working copy corrupt `target/` and the caches, and produce phantom reds. In a shared
working copy, batches that compile or run go **sequentially** — this is measured behavior, not a
precaution.

### Close the phase

Proposed seams stay **documented in the ADR as proposals**; they are applied in a separate issue
with its own approval, never here. Register the ADR in the target repo's `docs/adrs/`. Then go to
Phase 7 — and **do not commit**.
