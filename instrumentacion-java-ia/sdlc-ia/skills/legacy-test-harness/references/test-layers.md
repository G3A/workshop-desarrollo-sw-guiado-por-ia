# Test layers — what counts as real, and the floor per layer

Read only the section for the layer(s) chosen in Phase 4.

Two different bars appear below, and confusing them is how a run passes its own gate while the
census stays mostly untested:

- **The target** is the census (or the agreed tranche): every 🟢 actor ends `covered` or `excluded`.
- **The floor** is the minimum quality bar for the layer. Meeting the floor with the census half
  done does **not** pass the Phase 7 gate.

## Layer dependencies — warn about these in the Phase 4 menu

- **Contract** needs the **inventory of fakes** the unit layer introduces. Chosen without the unit
  layer, the inventory is built by censusing the doubles already in the repo — and if there are
  none, contract is reconsidered together with unit.
- **Acceptance** needs a **reachable SUT** (Phase 2, step 4).
- **Performance** and the **DAST** front of security need acceptance's SUT, or at least its setup.
- **SCA/SAST/secret scanning** depend on no other layer.

If the user picks a layer without its prerequisite, say so there and offer to add the dependency.

## Unit / collaboration tests (Rainsberger style)

Prove a class talks to its collaborators the way it's supposed to, using the Phase 3 seams — not
just that it returns a value. Default framework: JUnit 5 + Mockito (Java), Vitest or Jest (JS/TS),
pytest + `unittest.mock` (Python).

**Real means:** the class under test is the production class, imported from its real path. A
collaborator may be a double; the class under test never is.

**Floor:** at least `min(3, number of 🟢 actors)` real actors **carrying business logic**, with
doubles wired in by constructor or by reflection. A trivial utility does not satisfy the floor on
its own.

Extract a shared data builder to `testutil/builders/` before the second test that assembles the same
non-trivial entity. That is what keeps actor 15 costing what actor 3 cost.

## Contract tests (Rainsberger, no Pact)

The same suite runs against both the real collaborator and the double standing in for it elsewhere,
asserting both honor the same contract — this is what catches a double that drifted from what the
real dependency actually does.

**Real means:** there is a real instance of the collaborator (even local or in-memory) that the same
assertions run against, not just the double in isolation. The abstract suite types against the
**real interface from the repo** — or, where there is no interface, the concrete class.

**Floor:** for **every fake the unit layer introduced**, the abstract suite + the fake's contract
test green + the real side's test present. **Gated is not enough**: where the real side is viable,
name in the ADR the scheduled trigger that runs it (job or pipeline stage), its schedule, and the
date it last ran green. Without those three, the fake is self-certified and the layer misses its
floor. Where the real side is genuinely unviable without a seam, it stays gated and is **listed in
the ADR with its block**.

**Trivial mocks are exempt** — a double returning a constant with no behavior to drift. The
exemption is declared once, in the ADR's inventory of doubles, never as a comment in a test.

Where the contract is a law (ranges, idempotence, ordering, symmetry), add **properties**
(jqwik in Java, fast-check in TS, Hypothesis in Python) alongside the examples. Examples illustrate;
properties certify.

## Acceptance tests (Dave Farley's 4 layers)

Prove a full use case end-to-end through its real boundary. Layers: Test Cases (domain language) →
DSL → Protocol Drivers → System Under Test. Stand up real infrastructure with Testcontainers; double
third parties with WireMock.

Its job is the **assembly** — wiring, boundary serialization, transaction boundaries — **not**
re-verifying business rules. Those are already covered by unit plus contract, by composition.

**Real means:** the SUT is the actual application wired to real (containerized) infrastructure —
not an in-memory fake of the whole app.

**Floor:** 1 to 3 value paths end to end, with domain assertions (not `isNotNull`, not
`status < 500`). **The bar is the 1–3 paths.** The often-quoted "5–10% of the suite" is the expected
consequence in a healthy pyramid, not a target; where the two disagree, the paths win.

**If the SUT is not reachable, the layer is not generated** — it is proposed as an issue with
explicit prerequisites (a Dockerfile for the artifact, a seed, a login). No gated suites nobody can
turn on.

**If the natural boundary is the UI but no UI driver is viable** (e2e tooling blocked, no browser,
front not automatable), plan the same cases through an **HTTP driver against the endpoints that UI
consumes** — verified by grepping both front and back — and document the coverage delta.

## Performance tests (k6)

A risk **gate**, not exhaustive load testing: a threshold that fails the pipeline when a critical
path regresses past an agreed latency or throughput bound.

**Real means:** the script hits the actual service (or a containerized instance of it), not a stub
that always answers instantly. **Floor:** the endpoints it hits exist in the code — verify by grep,
don't invent routes.

## Security tests (SCA / SAST / DAST)

Reuse whatever SCA/SAST sensors the repo already has (see `instrument-project-java` for Java, or
`debt-triage` for triaging what they already report) and add a dynamic pass — OWASP ZAP — against a
running instance, for the layers above that expose an HTTP surface.

**Real means:** DAST runs against the actual running application from the acceptance setup, not a
static reading of the route table.

This layer is reported **per front, and its state can be `partial`**: SCA, SAST and secret scanning
don't depend on the SUT and can be generated while DAST stays blocked for lack of one. `partial` is
a real outcome here, not a softened failure — reporting the whole layer as blocked because one front
is would hide three sensors that do run.

## The layer gate

A layer is not "generated" until its own tests run and pass against the real target described above.
Run the layer's suite before moving to the next layer. The cross-layer gate is Phase 7.
