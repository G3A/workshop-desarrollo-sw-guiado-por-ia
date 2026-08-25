# Architecture fitness functions (ArchUnit)

> Called from **Phase 3** of `SKILL.md`, to install or discover control 7.

This turns the dependency rule from a diagram in `docs/architecture.md` into a **computational
sensor**: a test that goes red.

> **Read `architecture-discovery.md` first.** Which rules apply — and whether any of them do — is
> decided by the shape detected in Phase 1, not by this file. Every rule must pass against the
> current code before it is written.

ArchUnit and Spring Modulith's `ApplicationModules.verify()` are **complementary, not
alternatives**: `verify()` automatically polices the boundaries implied by `@ApplicationModule`
packages (who may depend on whom, cycles) the moment those annotations exist; ArchUnit polices
anything else the repo has decided but a compiler cannot enforce — "adapters only cross through the
facade", "`compartido` is a leaf", naming conventions. A Modulith repo normally has both.

---

## Two cases

### (a) No ArchUnit yet — install from scratch

1. Add the test dependency. **Do not add a bare `<version>`** if a BOM already manages ArchUnit;
   check first (`spring-modulith-bom` does not — `base-conocimiento` pins
   `<archunit.version>1.4.2</archunit.version>` explicitly, which is the common case):

   ```xml
   <dependency>
     <groupId>com.tngtech.archunit</groupId>
     <artifactId>archunit-junit5</artifactId>
     <version>${archunit.version}</version>
     <scope>test</scope>
   </dependency>
   ```

2. **Clone the repo's own test pattern if one exists.** `archunit-junit5` supports two styles —
   the annotation-driven `@AnalyzeClasses` + `@ArchTest`, and the manual `ClassFileImporter` +
   `@BeforeAll` + plain `@Test` that `base-conocimiento` uses (see the worked example below).
   Match whichever the repo's other test classes lean toward; **never `mvn archetype`** — there is
   no ArchUnit archetype worth trusting over a real sibling test.
3. **Verify-then-break-then-restore, in this order, before trusting a single rule:**
   - Add **one real dependency** that would violate a candidate rule (e.g. a `<dependency>` on the
     JDBC driver from what should be the pure-domain module).
   - Confirm `mvn compile` still succeeds — the dependency alone changes nothing observable yet.
   - Add the `import` and a real use of a type from it.
   - Run `mvn test -Dtest=<TheArchTestClass>` and confirm it **fails, naming the rule and the
     offending type**.
   - Restore all touched files: the source file, and `pom.xml` if you added a `<dependency>` (plus
     any new `<properties>` entry) — three files, not one.
4. Write the baseline rules from `architecture-discovery.md` §4 first; add the shape-specific ones
   only once those are green.

### (b) ArchUnit already wired — discovery, not installation

This is `base-conocimiento`'s actual state. The job is: read the rules, confirm they still pass,
and report what they leave uncovered — **not** write new rules unasked.

```bash
./mvnw test -Dtest=ArquitecturaTest
```

**Worked example — `src/test/java/co/g3a/baseconocimiento/ArquitecturaTest.java`.** Four rules,
JUnit 5 + manual `ClassFileImporter`:

```java
@BeforeAll
static void importar() {
    clases = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(RAIZ);
}
```

1. `losAdaptadoresNoConocenElNucleo` — `noClasses().that().resideInAnyPackage(web.., teams..)
   .should().dependOnClassesThat().resideInAnyPackage(recuperacion.., ingesta.., modelos.., llm..)`
2. `elNucleoNoConoceALosAdaptadores` — the reverse direction.
3. `compartidoEsHoja` — `compartido` depends on nothing else in the app.
4. `modulosValidos` — `ApplicationModules.of(BaseConocimientoApplication.class).verify()`.

**Two real findings to report, not fix silently:**

- **Rules 1 and 2 carry `.allowEmptyShould(true)`**, with an in-code comment explaining it is there
  "hasta que existan web/teams" (until `web`/`teams` exist). Both packages **already exist** under
  `src/main/java/co/g3a/baseconocimiento/`. The rule's trigger condition is already true, so the
  flag is now doing nothing except silently masking whatever the first violation would be — flip it
  to `false` only with the user's agreement, since the suite may go red the moment it is removed,
  and that call belongs to whoever owns the module boundaries.
- **The `seguridad` package is named in none of the four rules** — not a subject, not a target. It
  is neither protected from depending on the core/adapters nor prevented from being depended upon.
  Report this as a gap alongside the `allowEmptyShould` finding; do not add a fifth rule on your own
  initiative.

## Baseline rule catalogue (Java/Spring Modulith flavour)

| Rule | Why it matters |
|---|---|
| Adapters (web/UI/bot) do not depend on the core | The core must be replaceable without touching a persistence, retrieval, or LLM concern |
| The core does not depend on its adapters | It should not know which transport asked the question |
| Shared/common code is a leaf | If it depends on anything, it stops being shared vocabulary |
| `ApplicationModules.verify()` (if Modulith) | Boundary and cycle check the framework already knows how to do — do not hand-roll it |
| No direct ORM/JDBC type outside the persistence-facing package | Persistence stays a detail |

Start with the first two or three. Add the rest once those are green.

## Verification

Add a forbidden dependency to a real file — the clearest case is an infrastructure/ORM type used
inside a core service — and run `mvn test -Dtest=<TheArchTestClass>`. The failure message must name
the rule **and** the offending type; a message that only says "1 test failed" is not good enough to
report as a passing sensor.

That is the moment worth showing: **the agent asked for database access from the core, and the
repository refused on its own.**

Revert afterwards — see `verification.md` control 7 for the exact restore list.
