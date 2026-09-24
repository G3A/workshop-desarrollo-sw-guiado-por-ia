# Reality gate — proving a layer is real, not scaffolding

Phase 7, mandatory. Run it after writing, before reporting anything as generated.

Success is never declared on "it compiles and passes": **a self-contained skeleton always compiles
and always passes.** That is the false green this whole phase exists to catch.

Each step below reports **at most three lines** — the result only. The full breakdown goes in the
Phase 8 closing block.

## 7.1 — Compile and run the fast suite

Run the stack's fast suite. Everything green, and the gated slow tests **skip cleanly** — verify
that for real by running the slow profile *without* the gate variables set, not by assuming.

Three traps worth the extra command:

- **Clean the compiled test output first** (`target/test-classes` and equivalents). Classes from
  deleted tests keep running — a false green out of a file that no longer exists.
- **Confirm in the log that the newly generated tests actually ran**, by name, not a phantom suite.
- **Capture the run without the quiet flag.** `-q` suppresses the Surefire summary; the
  authoritative number is the `Tests run: N` on the console.

**Stability:** the fast suite passes **twice**, the second time in random order, with timezone and
locale pinned in the runner. A suite that only passes in one order is a suite that will go red on
somebody else's machine for reasons nobody will want to debug.

## 7.2 — Classify every generated test file: real or skeleton?

A test is **real** when its subject — the class it instantiates and exercises — comes from the
repo's production code.

Mechanical check: an import counts as production **only if it resolves to a file under the
production source root** (`src/main/...` in Java; an app source outside specs and test config on the
front end).

⚠️ **Do not verify by package prefix.** With the root-by-type layout, `unit/`, `contract/` and
`testutil/` share the root package with production — a skeleton importing nothing but
`testutil.FakeX` would sail past a grep for `import <root package>`.

**Transitive exception:** legitimate contract tests may not import production directly. Their
reality is verified one level up — the abstract suite they extend **must** type against a production
type. If the abstract suite doesn't either, the whole trio is a skeleton.

A test whose port, fake or SUT is **declared inside the test file itself** is a skeleton, even if it
imports utilities.

**The kill check.** Importing production is necessary, not sufficient — a test can import the real
class and still assert nothing that depends on it. For at least one assertion per new test file,
temporarily revert or comment out the production behavior it targets, run the test, and confirm it
**fails**. Restore production immediately afterwards: this check must never be the reason a
production file stays modified, and Phase 6's rule still holds while it runs.

A test that passes both before and after that revert is not testing what it claims to. It counts as
scaffolding, not as a generated layer — rewrite it or drop it from the count.

## 7.3 — Gate criteria

| Criterion | Passes when |
|---|---|
| **Census coverage** | every 🟢 actor of the agreed yardstick (full census, or the tranche) has real tests **or** an explicit justified exclusion. Meeting the per-layer floors with the yardstick half done does not pass. With a tranche, actors outside it must appear as `pending` — an actor with no state at all is a failed gate |
| **Coverage threshold** | the share of yardstick actors that end `covered` **and** are actually exercised (≥1 covered instruction) is at or above the agreed threshold — 80% unless the ADR says otherwise. Denominator = the yardstick minus `excluded` and `pending`; it **does** include `pending-seam` and `blocked`, which lower the percentage because they are inside the yardstick without a real test |
| Per-layer floor | every chosen layer with 🟢 candidates meets its floor from `test-layers.md` |
| Non-trivial subject | the unit layer covers business logic, not only pure utilities |
| Business assertions | assertions state the expected state or result; no bare `isNotNull`, no `status < 500` |
| Behavior names | a domain phrase with condition and outcome; not `testFoo`, not the method's name |
| Synchronization rule | every fake used in the unit layer has its contract test against the repo's real surface; trivial mocks are exempt only via the ADR's inventory of doubles |
| Doubles inventory | the ADR's inventory matches the doubles actually in the tree, including the ones invisible by filename |
| Boundaries verified | the endpoints and routes acceptance and k6 use **exist in the code** (grep the route annotations), and the expected statuses are what the real boundary returns — a legacy JAX-RS resource with no exception mapper answers 200/500; don't invent a 201 or a 409 |
| Two speeds | the fast suite runs with no container runtime; the slow tests skip cleanly without the profile |
| Layout by type | every test lives under its type's root per the layout |
| Zero production touched | `git diff` touches only test paths, pipeline/sensor config and the build file's test surface — never the production source root, absent explicit prior approval |
| ADR complete and resumable | the ADR carries the census appendix with a state per actor, the tranche, the preflight with its pinned runtime, the execution conventions, the gates, and any accepted shortfall |
| Seams have nets | every proposed seam carries its characterization test — generated where the boundary is reachable, specified in the issue otherwise |

**Output:** the table is the evaluation reference, not the output. Print a short verdict plus only
the criteria that **fail** — `Gate: 2/14 fail → census coverage (actor X has no state), stability
(flaky)`. If it passes, `Gate: all criteria met`.

## 7.4 — If the gate fails

Go back to Phase 5 for the deficient layer(s) and generate the real tests that are missing. Do
**not** close, and do **not** report that layer as generated.

The per-actor retry budget from Phase 6 applies. Once it's spent, the actor — or the layer, if the
defect is the layer's — is reclassified `blocked` with its diagnosis. A genuine block (an unapproved
🔴 seam, an environment block from Phase 2) leaves the layer **blocked**, with its justification.
Never an infinite loop, never a silent abandonment.

## 7.5 — Coverage

Measure **which census actors ended up exercised**, with the stack's coverage tool (JaCoCo,
`--coverage`, `coverage.py`), excluding generated and bootstrap code.

The **global percentage is not a gate** — on a legacy repo it will always be low, and it is reported
as evidence. What **is** a gate is the share of yardstick actors that end `covered`, per the
threshold row above.

If Docker was available and the slow suite really ran, merge its report with the fast one so
contract-real-certified actors count. Without Docker, those actors are reported as not measurable
rather than as uncovered.

Output shape: `Coverage: 8/9 census actors exercised · not covered: NotificadorSMS`.

## 7.6 — Mutation testing (informative, recommended, not a gate)

Coverage says which lines ran; mutation says whether the suite **detects defects**. Run it if the
stack allows it without friction (PITest, Stryker), **scoped to the classes the unit layer covers**
— not the whole legacy repo. Report the mutation score and the notable survivors: a survivor inside
a business rule is a missing assertion.
