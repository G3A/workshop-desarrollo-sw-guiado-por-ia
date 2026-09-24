# Seam mapping — Feathers' lens, per stack

A seam is a place where you can alter behavior without editing the line itself. Walk each actor of
the census, look at every I/O boundary it touches, and classify that boundary into one of three
states.

## 🟢 fakeable today

The collaborator arrives through a seam the test can use **right now, with no production edit**:

1. **Constructor or setter injection already present** — free: pass a test double.
2. **An interface or abstract class between the caller and the real dependency** — hand the test a
   fake implementation.
3. **A collaborator sitting in an injected field** (`@Autowired`, `@EJB`, `@Inject`,
   `@PersistenceContext` on the field, and the equivalents in other stacks) — the field is
   populated **by reflection from the test**, with production untouched.

⚠️ **Classifying field injection as 🟡 is the mistake that leaves whole layers without real tests.**
Do not make it. The constructor seam can still be *proposed* as an improvement, but it is not a
prerequisite for generating the test, and treating it as one blocks every actor in a typical
dependency-injected legacy codebase.

## 🟡 needs a seam

`new` called directly inside the method under test, a static call, a service-locator or JNDI lookup,
a singleton. Reachable only by changing production — **unless** the language offers a reflection
seam (table below). Record the recommended Feathers technique **as a proposal**, and do not apply
it.

## 🔴 irreducible

Behavior that can't be reproduced in a test at all: a call into unreproducible native code, a
dependency on wall-clock real-time ordering, an external system with no substitute. Recommend
revisiting the port boundary itself, and record it as `blocked` with the diagnosis.

## Reflection seams — cuttable without a production edit

These move a boundary from 🟡 to 🟢. Check for one before concluding a seam needs an edit.

| Stack | Technique |
|---|---|
| Java | `Field.setAccessible(true)` + `field.set(target, double)` to replace a private collaborator the constructor never exposed. Static and singleton calls: a test-only subclass overriding the one method that reaches the static call. |
| Spring | Override the bean in a `@TestConfiguration`, or replace it post-construction with `ReflectionTestUtils.setField`. |
| Angular / AngularJS | `TestBed.overrideProvider` (Angular) or `$provide.value` (AngularJS) — DI seams the framework already gives you; reflection is rarely needed. |
| Node / TypeScript | Module-level mocking (`jest.mock`, `vi.mock`, `proxyquire`) replaces an imported module without touching the source file. |
| Python | `unittest.mock.patch` targets any attribute, including module-level singletons and classmethods. |
| Go | Only if the dependency is already an interface field — Go has no reflection seam for a concrete call. Usually a genuine 🟡. |

## Every proposed seam carries its net

A seam proposed without a characterization test is a blind refactor handed to whoever picks up the
issue. For each 🟡 and 🔴, record its **characterization test**: the observable boundary reachable
**today**, without the seam, through which current behavior can be pinned.

- **Find the reachable boundary** — a public method that wraps the logic, an existing endpoint, a
  CLI entry point. If one exists, generate the characterization test in Phase 6.
- **Pick the technique** — golden master (record current outputs to a fixture and assert against
  them) for wide or ugly output; a table of cases for narrow behavior.
- **Pin what IS, not what should be — bugs included.** A characterization test is a refactor net,
  not a specification. If current behavior is wrong, the test asserts the wrong value and says so in
  a comment. Correcting it here silently turns the net into a second change nobody approved.
- **If no boundary is reachable**, the characterization can't be generated. Then it is *specified*
  in the proposed issue — which boundary to pin and with what technique — as that issue's **first
  acceptance criterion**.

Characterization tests are **temporary**. The sequence the issue follows is: pin → apply the seam →
write real collaboration tests → delete the golden master. They do not count toward census coverage.

## When no seam exists without a production edit

Do not make the edit. Record, for the Phase 8 report:

- The exact location (`file:line`) and what the seam would need — "extract the `HttpClient`
  construction in `OrderService` to a constructor parameter".
- Why no reflection or DI alternative applies here.
- Its characterization net: generated, or specified as the issue's first acceptance criterion.

That becomes one candidate issue — not an edit made in passing while "just adding a test".
