# Java inspection checklist (continued)

> Continues `references/java-inspection.md` §1–§11. Called from **Phase 1** of `SKILL.md`. Same
> conventions: what to read, what to extract, how to record it; read real files, leave
> `<!-- TODO -->` where a fact isn't readable, never guess.

## 12. Deployment & packaging

- **Read:** the Spring Boot Maven/Gradle plugin config (`<layers><enabled>true</enabled></layers>`
  for a layered jar); `Dockerfile`; `docker-compose*.yml`; native-image config
  (`org.graalvm.buildtools.native`); a WAR packaging (`<packaging>war</packaging>`) for app-server
  deployment.
- **Extract:** **layered jar** — Spring Boot's layered-jar feature splits dependencies/resources/
  application into Docker layers so only the application layer rebuilds on a code change; record
  whether it's enabled. **Native image** — GraalVM native-image build, if configured, and the
  extra reflection-config constraints it imposes (no unbounded reflection). **WAR vs jar** — a WAR
  for deployment to WildFly/Tomcat/Payara vs a self-contained Spring Boot fat/layered jar.
- **Record:** how an image or artifact is actually produced. Native-image constraints belong in
  **gotchas** (§14), not just facts.

## 13. Cross-cutting concerns

- **Read/Grep:** observability (`spring-boot-starter-actuator`, `micrometer-*`,
  `opentelemetry-*`); resilience (`resilience4j-*`, Spring Retry); messaging (`spring-kafka`,
  `spring-rabbit`, JMS); AI-shaped signals (`spring-ai-*`, a `.mcp.json` at the repo root).
- **Extract/Record:** one short list of what's wired up and where.

## 14. Gotchas / hotspots

- **Read/Grep:** unusually large files, high-churn files if git history is available, `// TODO`/
  `// FIXME` density, deeply nested exception handling.
- **Extract:** monolithic classes, any Modulith boundary the verify test allows empty today
  (`allowEmptyShould(true)`) that will start enforcing once a package appears, native-image/
  reflection constraints from §12. If the guarded package **already** has real classes — the
  condition the flag was waiting for already happened — don't just note it here: carry it into
  Phase 5 as an actionable claim (`references/claim-validation.md` §1), same as any other finding.
- **Record:** a short "gotchas" list — the non-obvious things that will trip up an agent here.
