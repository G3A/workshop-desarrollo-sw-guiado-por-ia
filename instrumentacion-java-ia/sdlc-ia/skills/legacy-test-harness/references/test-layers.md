# Test layers — what counts as real, per layer

Read only the section for the layer(s) chosen in Phase 3.

## Unit / collaboration tests (Rainsberger style)

Purpose: prove a class talks to its collaborators the way it's supposed to, using the seams from
Phase 2 — not just that it returns a value. Default framework: JUnit 5 + Mockito (Java), Jest
(JS/TS), pytest + `unittest.mock` (Python). Real means: the class under test is the production
class, imported from its real path; a collaborator may be a double, the class under test never is.

## Contract tests (Rainsberger, no Pact)

Purpose: the same test suite (or an equivalent one) runs against both the real collaborator and the
double standing in for it elsewhere, asserting both honor the same contract — catches a double that
drifted from what the real dependency actually does. Real means: there exists a real instance of
the collaborator (even a local/in-memory one) that the same assertions run against, not just the
double in isolation.

## Acceptance tests (Dave Farley's 4 layers)

Purpose: prove a full use case end-to-end through its real boundary. Layers: Test Cases (domain
language) → DSL → Protocol Drivers → System Under Test. Stand up real infrastructure with
Testcontainers; double third parties with WireMock. Back-end: an HTTP driver against the real app.
Front-end: a UI driver (see the `pruebas-de-aceptacion` skill's own references if this run has
access to it) against a real running build. Real means: the SUT is the actual application wired to
real (containerized) infrastructure — not an in-memory fake of the whole app.

## Performance tests (k6)

Purpose: a risk *gate*, not exhaustive load testing — a threshold that fails the pipeline if a
critical path regresses past an agreed latency/throughput bound. Real means: the script hits the
actual service (or a containerized instance of it), not a stub that always answers instantly.

## Security tests (SCA / SAST / DAST)

Purpose: reuse whatever SCA/SAST sensors the repo already has (see `instrument-project-java` for
Java, or `debt-triage` for triaging what they already report) and add a dynamic pass — OWASP ZAP —
against a running instance for the layers above that expose an HTTP surface. Real means: DAST runs
against the actual running application from Phase 4's acceptance setup, not a static reading of the
route table.

## Layer gate (all layers)

A layer is not "generated" until its tests run and pass against the real target described above.
Run the layer's own suite before moving to the next layer or reporting in Phase 6.
