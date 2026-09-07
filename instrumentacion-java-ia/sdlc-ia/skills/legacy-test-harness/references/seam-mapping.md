# Seam mapping — Feathers' lens, per stack

A seam is a place where you can alter behavior without editing the line itself. Walk the target
module looking for these, in order of how cheap they are to cut from a test:

1. **Constructor/setter injection already present** — cuttable for free: pass a test double.
2. **An interface or abstract class between the caller and the real dependency** — cuttable: hand
   the test a fake implementation.
3. **A concrete class instantiated with `new` inside the method under test** — usually requires a
   production edit (extract the construction to an injectable point) *unless* the language offers a
   reflection-based seam (below).
4. **A static method call or a singleton** — same as (3): needs either a production edit or a
   reflection-based seam.

## Reflection-based seams (cuttable without a production edit)

| Stack | Technique |
|---|---|
| Java | Field injection via `Field.setAccessible(true)` + `field.set(target, testDouble)` to replace a private collaborator the constructor never exposed. Static/singleton calls: `PowerMock`-style bytecode mocking, or wrap the call inside a test-only subclass that overrides the one method that reaches the static call. |
| Spring | Override a bean in a `@TestConfiguration`, or replace it post-construction via `ReflectionTestUtils.setField`. |
| Angular / AngularJS | `TestBed.overrideProvider` (Angular) or `$provide.value` (AngularJS) — DI seams the framework already gives you; rarely needs reflection. |
| Node / TypeScript | Module-level mocking (`jest.mock`, `proxyquire`) replaces a required/imported module without touching the source file. |
| Python | `unittest.mock.patch` targets any attribute, including module-level singletons and classmethods, without a production edit. |

## When no reflection-based seam exists

If the only way to cut the seam is genuinely editing production code (e.g. a language with no
reflection and no DI container already in place), do not make the edit. Record:

- The exact location (file:line) and what the seam would need (e.g. "extract the `HttpClient`
  construction in `OrderService` to a constructor parameter").
- Why no reflection/DI-based alternative applies here.

This becomes one line in Phase 6's report, filed as a candidate issue — not an edit made in passing
while "just adding a test".
