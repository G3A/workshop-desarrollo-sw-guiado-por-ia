# Java inspection checklist

> Called from **Phase 1** of `SKILL.md`.

The discovery work behind `docs/java.md`. Each section says **what to read**, **what to
extract**, and **how to record it**. Read real files (Glob/Grep/Read). Where a fact isn't
readable, leave a `<!-- TODO -->` — never guess.

**Every section is conditional.** Inspect only what the repo actually signals, and carry that
through to the document: a single-module Spring Boot app should produce a `docs/java.md` with the
multi-module and Spring Modulith sections **deleted**, not filled with TODOs. A short accurate doc
beats a long hedged one.

## Adjacent signals

A Java repo is rarely only Java. These feed `architecture.md` and `infrastructure.md`:

| Signal | Infer |
|---|---|
| `Dockerfile`, `docker-compose*.yml`, `compose.yaml` | Containerization (layered jar or fat jar — see §11) |
| `Chart.yaml`, `values.yaml`, `k8s/`, `kustomization.yaml` | Kubernetes / Helm |
| `*.tf`, `terraform.tfvars`, `cdk.json`, `serverless.yml`, `template.yaml` | Infrastructure as Code |
| `.github/workflows/`, `azure-pipelines*.yml`, `Jenkinsfile`, `.gitlab-ci.yml` | CI/CD |
| `*.wsdl`, `*.xsd`, `*.proto`, `openapi.yaml`, `swagger.json` | API contract style (SOAP, gRPC, REST) |
| `package.json`, `angular.json`, `vite.config.*` | JS/TS frontend alongside the Java backend |
| `sonar-project.properties`, `checkstyle.xml`, `.editorconfig` | Quality gates (details in §9) |
| `Makefile` | Dev-facing shortcuts over Maven/Gradle/Docker commands — prefer these in AGENTS.md |

---

## 1. Modules (Maven / Gradle)

- **Read:** `pom.xml` at the root; if it has `<modules>`, every child `pom.xml` (multi-module
  Maven). `settings.gradle`(`.kts`) for `include(...)`; every module's `build.gradle`(`.kts`)
  (multi-module Gradle). A single `pom.xml`/`build.gradle` with no children means single-module.
- **Extract:** the module list and, for Maven, `<parent>` inheritance (`spring-boot-starter-parent`
  or an internal parent POM); classify each module by its dependencies/plugins (web app, library
  with no main class, test-only, or a Spring Modulith host — see §6); for multi-module repos, the
  inter-module dependency graph (`<dependencies>`/`implementation`).
- **Record:** a module table (name · type · one-line purpose) and, for multi-module repos, a small
  Mermaid graph. For single-module repos with Spring Modulith, skip this table — §6 covers the
  package-level module graph instead.

## 2. JDK target & language level

- **Read:** `pom.xml` properties (`<java.version>`, `<maven.compiler.release>`,
  `<maven.compiler.source>`/`<target>`); `build.gradle` (`sourceCompatibility`,
  `targetCompatibility`, `java.toolchain.languageVersion`); `.sdkmanrc`; `.tool-versions`.
- **Extract:** the JDK release the build targets, and whether it differs from the JDK actually
  installed in CI/Docker (e.g. `<java.version>21</java.version>` but a Dockerfile `FROM
  eclipse-temurin:17`).
- **Record:** the JDK target; flag any declared-vs-used mismatch as an ambiguity for claim
  validation.

## 3. Dependency & BOM management

- **Read:** `<dependencyManagement>` in `pom.xml` (including `<scope>import</scope>` BOM
  entries); Gradle's `platform(...)`/`enforcedPlatform(...)` in a `dependencies` block, or a
  `java-platform` module; `<properties>` holding pinned versions; the Maven/Gradle wrapper files
  (`.mvn/wrapper/maven-wrapper.properties`, `gradle/wrapper/gradle-wrapper.properties`).
- **Extract:** which BOMs are imported (Spring Boot's parent, Spring Modulith BOM, Spring AI BOM,
  Testcontainers BOM, etc.) and whether versions are pinned via `<properties>` with **no ranges**
  — record as an *editing rule* for AGENTS.md: a dependency managed by a BOM or
  `<dependencyManagement>` **must not carry its own `<version>`**, one of the things an agent most
  reliably gets wrong. Whether `./mvnw`/`./gradlew` is committed and which version it pins (prefer
  the wrapper over a bare `mvn`/`gradle` in Commands). Load-bearing dependencies, grouped — web,
  persistence (`spring-boot-starter-data-jpa`, `-jdbc`, jOOQ), messaging, security, observability
  (`spring-boot-starter-actuator`, `micrometer-*`) — with versions.
- **Record:** a short highlights list (not the full dump) and the BOM/central-management rule if
  it applies. Note version freshness or known-vuln risk **only** with a concrete signal — never
  invent a CVE.

## 4. Framework posture: Spring Boot vs Jakarta EE vs plain Java

- **Read:** `pom.xml`/`build.gradle` for `spring-boot-starter-*` vs `jakarta.*`/`javax.*` EE APIs
  with no Spring Boot parent; `@SpringBootApplication` vs `web.xml`/EJB descriptors; an app-server
  config (WildFly `standalone.xml`, Payara `domain.xml`).
- **Extract:** the posture — Spring Boot (embedded server), Jakarta EE on an app server, or a
  plain Java library/CLI with no framework.
- **Record:** the posture; it changes how §5–§10 are read (DI is constructor injection + `@Bean`
  under Spring, CDI `@Inject`/`@Produces` under Jakarta EE).

## 5. DI / composition root

- **Read:** `@SpringBootApplication` main class; `@Configuration` classes and their `@Bean`
  methods; constructor-injected `@Service`/`@Component`/`@Repository` classes; `@Profile`
  conditionals; for Jakarta EE, CDI `@Inject`/`@Produces`/`beans.xml`.
- **Extract:** where beans are wired (a few central `@Configuration` classes vs scattered
  component scanning), the DI convention (constructor injection is idiomatic Spring; field
  injection is a smell worth flagging), and conditional wiring (`@Profile`,
  `@ConditionalOnProperty`).
- **Record:** where the composition root lives and any DI convention an agent must follow.

## 6. Spring Modulith module boundaries

Skip entirely if `org.springframework.modulith` is absent — it has no .NET equivalent, don't
force it.

- **Read:** `package-info.java` under each top-level package for
  `@org.springframework.modulith.ApplicationModule`; a test calling
  `ApplicationModules.of(...).verify()` (usually `*ArchitectureTest`/`*ArquitecturaTest`).
- **Extract:** each top-level package carrying `@ApplicationModule` is a **declared module** —
  record its `displayName` and one line from its package-level Javadoc. Note whether the verify
  test runs in the default test phase (fails the build on violation) or is skipped.
- **Record:** treat this as the module graph for single-module repos (§1's table). State
  explicitly in `docs/java.md` and AGENTS.md: *a package may only be reached through the module's
  public API — reaching into another module's internal package is the violation this test
  catches.*

## 7. Persistence

- **Read:** `@Entity`/`@Table` classes; Spring Data repository interfaces
  (`JpaRepository`/`CrudRepository`/`JdbcRepository`); hand-written SQL via
  `JdbcClient`/`JdbcTemplate`; jOOQ generated code; `persistence.xml` (Jakarta EE); Flyway/Liquibase
  migration folders.
- **Extract:** the persistence approach — JPA/Hibernate (annotation-mapped entities, migrations
  via Flyway/Liquibase or `ddl-auto`), Spring Data JDBC/plain `JdbcClient` (hand-written SQL, no
  ORM session), or jOOQ (generated typesafe SQL) — and where migrations live and how they're
  applied (startup vs CI vs `flyway:migrate`/`liquibase:update`).
- **Record:** framework + migration tool + workflow. **Flag a very large repository/entity class**
  as a hotspot. Cross-link `docs/data-model.md` for the schema.

## 8. Configuration & profiles

- **Read:** `application.yml`/`.properties` and `application-{profile}.yml` variants;
  `@ConfigurationProperties` classes; `.env`/`.env.example`; secrets-manager client dependencies.
- **Extract:** the profile layering (default → per-environment → env vars/secrets manager), what
  each overrides, the default active profile, and the flag to switch it
  (`--spring.profiles.active=`, `SPRING_PROFILES_ACTIVE`).
- **Record:** the config/secrets strategy. **Flag any secret that appears committed** — recommend
  env vars / a secrets manager and an `.env.example`. Never reproduce the secret value.

## 9. Build, run, test

- **Read:** `pom.xml`/`build.gradle` plugin config; `mvnw`/`gradlew`; a `Makefile` if present; CI
  workflow files.
- **Extract:** the everyday commands — build, run (`spring-boot:run`/`bootRun`), test, migrate; a
  `Makefile` wrapping these (`make build`/`test`/`verify`) is the house convention, prefer it over
  raw invocations. **Unit vs integration split** — Surefire (`**/*Test.java`) vs Failsafe
  (`**/*IT.java`) in Maven, or separate `test`/`integrationTest` source sets in Gradle; record
  which command runs which, since a plausible-looking wrong test command wastes an agent's whole
  loop. Test framework (JUnit 5 / TestNG) plus supporting libraries: mocking (Mockito), assertions
  (AssertJ), architecture tests (ArchUnit), property-based (jqwik), containers (Testcontainers),
  HTTP stubs (WireMock).
- **Record:** the 3–6 commands a developer actually runs, the runner/framework(s), and how tests
  are organized.

## 10. Quality gates

- **Read:** `checkstyle.xml`, `.editorconfig`; Spotless config in `pom.xml`/`build.gradle`;
  SpotBugs/PMD plugin config; ArchUnit test classes; SonarQube (`sonar-project.properties`).
- **Extract:** which gates are **present** vs **absent**. A repo with only an ArchUnit test and
  no Checkstyle/Spotless/SpotBugs/PMD is a real, common config — record it as-is ("ArchUnit
  present, nothing else"), don't imply more coverage than exists.
- **Record:** a present/absent table. Where absent, recommend Checkstyle or Spotless for
  formatting and an ArchUnit test (or `ApplicationModules.verify()`, if modules exist) for
  layering.

## 11. Web / API surface

- **Read:** `@RestController`/`@Controller` classes; `springdoc-openapi`/`swagger` dependencies;
  `@RequestMapping` trees; GraphQL (`graphql-java`, `.graphqls`); gRPC `.proto` files.
- **Extract:** the API shape (REST vs GraphQL vs gRPC), endpoint grouping, validation approach
  (`spring-boot-starter-validation`), and the OpenAPI generator (`springdoc-openapi` is the modern
  default; older repos may carry `springfox`).
- **Record:** the API technology and any wiring an agent must respect. Omit entirely for headless
  services (batch jobs, CLIs, libraries).

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
  reflection constraints from §12.
- **Record:** a short "gotchas" list — the non-obvious things that will trip up an agent here.
