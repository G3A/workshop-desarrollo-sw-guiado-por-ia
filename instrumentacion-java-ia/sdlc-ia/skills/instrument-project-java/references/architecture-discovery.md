# Deriving architecture rules from the repository

> Called from **Phase 1** (classify the shape) and **Phase 3** (derive the rules) of `SKILL.md`.

Control 7 fails in one specific way: assuming an architecture the repo does not have. Rules copied
from a layered template land red on day one in a repo organised by module, and the team learns
that arch-tests are noise.

**The rule: encode what the repository already does, not what it should do.** Every rule you write
must pass the moment you write it.

---

## 1. Build the dependency graph — two paths

**Path A — Spring Modulith is present** (`spring-modulith-starter-core` or
`spring-modulith-starter-test` in the POM). The module graph already exists: every top-level
package under the application's base package that carries a `package-info.java` annotated
`@org.springframework.modulith.ApplicationModule` is a declared module.

```bash
# Every declared module, by convention one top-level package per business capability.
find src/main/java -name package-info.java
grep -l "@org.springframework.modulith.ApplicationModule" $(find src/main/java -name package-info.java)
```

`ApplicationModules.of(<Application>.class).verify()` (control 7's fourth rule in the worked
example below) already checks the Modulith-level boundary and cycle rules **automatically** —
it does not need you to write per-pair rules by hand. Anything beyond that (which packages may
call which) is still a hand-written ArchUnit rule.

**Path B — no Modulith.** Two sub-cases:

- **Multi-module Maven** — the graph is the same one built in `inspection.md` §3: read every
  child module's `<dependency>` on a sibling `<artifactId>`.
- **Single module, plain packages** — the weakest evidence, still real: group `import` statements
  by top-level package under the root, per file (`grep -rhoE '^import co\.g3a\.[a-z]+' src/main/java`
  or the repo's actual root package). This infers a graph from usage, not declaration — treat any
  rule derived from it as more tentative and say so when confirming with the user.

## 2. Read what the repo says about itself

`docs/architecture.md`, `docs/adrs/`, `AGENTS.md` ("non-obvious rules" section), the README.

**When the docs and the graph disagree, the graph wins** — and the disagreement is a finding worth
reporting. Documented-but-violated is exactly the drift these tests exist to stop.

## 3. Classify the shape

| Shape | Signals | Rules that fit |
|---|---|---|
| **Modular monolith (Spring Modulith)** | `@ApplicationModule` package-infos; module packages named after a business capability (`recuperacion`, `ingesta`, `web`, `teams`) | `ApplicationModules.verify()` for boundaries/cycles; hand-written rules for "who may call the facade" |
| **Layered** (hexagonal/onion) | Packages like `dominio`/`nucleo`, `aplicacion`, `adaptadores`, `infraestructura`, `web` | Core depends on nothing; adapters depend on the core, never the reverse; core does not reference the ORM/HTTP client directly |
| **Multi-module Maven, one module per layer/feature** | Several `pom.xml` under one parent, `<module>` list | Same direction rules as layered, expressed as module dependencies instead of package rules |
| **Flat / no discernible structure** | One module, no layering, no feature grouping | Only the universal rules below |

Do not force a repo into a shape. If the signals are mixed or weak, say so and fall back.

## 4. Universal rules — safe in any shape

- **No cycles** between modules or namespace slices.
- **Shared/common code is a leaf** — nothing it depends on may depend back on it (`compartido`-style
  packages, `common`, `shared-kernel`).
- **Production code does not depend on test code.**
- **Adapters/entry points do not leak into the core** — whatever the repo calls its transport layer
  (`web`, `api`, `teams`, a REST controller package) must not be imported from the domain/service
  packages.

## 5. Verify before proposing

For each candidate rule, **evaluate it against the current code before writing it into the test
class.**

| Result | Action |
|---|---|
| Passes | Write it. This is the rule you wanted |
| Fails with 1–2 violations | Report the violations to the user and ask: fix the code now, or skip the rule? Do not decide alone |
| Fails broadly | Do not write it. Report it as a finding: "the docs say X; N types violate it." That is a conversation, not a test |

This step is what keeps the suite green on install.

## 6. Confirm with the user

Present the detected shape, the evidence, and the proposed rules before writing the project.

> Detected shape: **Spring Modulith modular monolith**, 9 declared modules.
> Evidence: `package-info.java` under `compartido`, `ingesta`, `llm`, `modelos`, `orquestacion`,
> `recuperacion`, `seguridad`, `teams`, `web`, each `@ApplicationModule`.
>
> Proposed / already-present rules:
> 1. Adapters (`web`, `teams`) do not depend on the core (`recuperacion`, `ingesta`, `modelos`, `llm`)
> 2. The core does not depend on the adapters
> 3. `compartido` is a leaf
> 4. `ApplicationModules.verify()` — Modulith boundary and cycle check
>
> Not covered by any rule: the `seguridad` package is neither a named subject nor a named target
> in rules 1–3 — worth a conversation before extending the suite, not a silent addition.

## 7. Worked example — `base-conocimiento`

Spring Modulith is already wired: `spring-modulith-starter-core` + `spring-modulith-starter-test`,
one `package-info.java` per top-level package under `co.g3a.baseconocimiento`
(`compartido`, `ingesta`, `llm`, `modelos`, `orquestacion`, `recuperacion`, `seguridad`, `teams`,
`web`). `ArquitecturaTest.java` already encodes rules 1–4 above verbatim. See `arch-tests.md` for
what that file gets right and the two findings it leaves open — this is control 7's **discovery**
case, not its installation case.

## 8. When there is no architecture to protect

A repo with one module and no layering does not need six arch rules. Write the universal ones, say
plainly that the shape does not support more, and move on. Padding the suite with rules that assert
nothing is worse than a small suite — it teaches the team that the tests are decoration.
