# Claim validation (Claimify-inspired)

> Called from **Phase 5** of `SKILL.md`.

A procedure to catch hallucinations in generated documentation **before** the user trusts it.
You read the repository (the *source of truth*); the user is the human verifier for anything you
could not ground in a file.

Adapted from Microsoft Research's **Claimify** ("Towards Effective Extraction and Evaluation of
Factual Claims", Metropolitansky & Larson, ACL 2025 — arXiv:2502.10855). Three of its principles
drive this step:

- **Atomic & self-contained.** Each claim is one fact, understandable without the surrounding doc.
- **Verifiable & faithful.** A claim must be entailed by the source (a file you read). If it is
  not in the repo, it is not a fact — it is a guess.
- **Flag ambiguity instead of guessing.** When a sentence has more than one plausible reading, or
  no clear source, do **not** silently assert it. Surface it for confirmation.

> This procedure is intentionally duplicated across every skill that generates docs (skills ship
> independently). If you change it here, mirror the change in the other skills' copies.

---

## Step 1 — Select the load-bearing claims

Re-read the docs you just wrote. Extract only **factual, verifiable** statements. Skip
`<!-- TODO -->` markers, generic boilerplate (e.g. "do not commit secrets"), and opinions.

Prioritize the claims that an agent will act on and that are expensive if wrong:

- **Build tool + version** — Maven or Gradle, and the wrapper version pinned
  (`.mvn/wrapper/maven-wrapper.properties`, `gradle/wrapper/gradle-wrapper.properties`).
- **JDK target** — the compiler release (`<maven.compiler.release>`, `sourceCompatibility`) and,
  if pinned, the JDK the CI/dev environment actually runs.
- **Persistence framework** — JPA/Hibernate, Spring Data JDBC, jOOQ, or plain JDBC, plus the
  migration tool (Flyway/Liquibase) and its workflow.
- **DI framework** — Spring (constructor injection vs `@Bean` methods) or a DI container.
- **The 3–6 most-run developer commands** — especially the wrapper invocation, the
  profile-activation flag, and the unit/integration test split when a non-default runner or
  Failsafe is configured.
- **Key domain entities** and their relationships.
- **Dependency versions** you named, and whether they're managed centrally via a BOM /
  `<dependencyManagement>` / Gradle platform.
- **The user-supplied non-obvious rules / invariants** (module-layering rules, Spring Modulith
  boundaries, DI lifetimes).
- **An obsolete `allowEmptyShould(true)`** (§14 of the Java checklist, in
  `references/java-inspection-2.md`) — a Modulith boundary
  flagged empty whose guarded package now holds real classes. Not a claim about the docs, but list
  it in the ledger anyway: it's an actionable finding, and discovery findings don't get dropped
  just because they surfaced outside the interview.

Aim for the ~8–15 highest-stakes claims, not an exhaustive list.

## Step 2 — Atomize and tag provenance

Rewrite each as **one** self-contained sentence, then attach:

- **Source** — `path/to/file:line` if you read it there, or `inferred` if you deduced it from
  indirect signals, or `user` if it came from the interview.
- **Confidence**:
  - `high` — read verbatim/near-verbatim from a file (e.g. a version pinned in `pom.xml`).
  - `medium` — inferred from a single weak or indirect signal.
  - `low` — guessed, or supplied by the user but unverified against the repo.

Example: `"The project targets JDK 21."` → source `pom.xml:24`, confidence `high`.

## Step 3 — Flag ambiguity

Mark a claim **ambiguous** when:

- More than one interpretation is plausible (e.g. both `pom.xml` and `build.gradle` are present;
  an entity name is overloaded across modules).
- You wrote it but cannot point to a concrete source.

Ambiguous and `low`-confidence claims **must** be resolved with the user or downgraded — never
left as a bare assertion.

## Step 4 — Verify with the user

Present a compact ledger of the claims, grouped by confidence. Then:

- For the **top few binary, high-stakes** confirmations, use `AskUserQuestion` (e.g. "Is
  persistence JPA/Hibernate or Spring Data JDBC?"). Offer the candidate readings as options.
- For the rest of the `medium`/`low`/ambiguous claims, ask in plain chat: list them numbered and
  ask the user to reply with corrections (e.g. "3 and 5 are wrong: …").
- `high`-confidence claims with a concrete source ref are shown for transparency but do **not**
  block — the user can still correct them.

Keep it short. Do not ask the user to re-confirm things you read directly from a file unless they
conflict.

## Step 5 — Apply corrections

- Write every correction back into the relevant doc.
- For any claim left **unconfirmed** after asking (user skipped, or still uncertain): replace the
  asserted text with a `<!-- TODO: verify — <claim> -->` marker. Do not assert it.
- Re-check that corrected claims didn't break cross-doc consistency (e.g. a JDK version named in
  both `architecture.md` and `java.md`).

## Step 6 — Persist the ledger

Write (or, in augment mode, append to) `docs/claims-ledger.md` so there is an audit trail of what
was verified and what is still open.

Generated docs are always in Spanish (see `SKILL.md`), so the ledger is too:

```markdown
# Registro de afirmaciones

Generado por la skill `agent-context-java`. Registra las afirmaciones factuales clave de la
documentación, su fuente en el repositorio y si fueron confirmadas por una persona. Vuelve a
ejecutar la skill para actualizarlo.

| Afirmación | Fuente | Confianza | Estado |
|---|---|---|---|
| El proyecto apunta a JDK 21. | `pom.xml:24` | alta | confirmada |
| La persistencia es JPA/Hibernate. | inferido | media | corregida → Spring Data JDBC |
| Las migraciones de Flyway corren al arrancar. | usuario | baja | TODO: verificar |
```

Status values: `confirmed` / `corrected → <new value>` / `TODO: verify` (or the Spanish
equivalents: `confirmada` / `corregida → …` / `TODO: verificar`).

---

## Rules

- The repository is the source of truth. Never invent a version, dependency, endpoint, or schema
  detail you did not read.
- Prefer a `<!-- TODO: verify -->` marker over a confident-but-unverified assertion.
- Keep the interview lightweight — verify the few claims that matter, not everything.
