# Java — Base de Conocimiento

Profundidad técnica del stack Java/Spring. Para el panorama general ver
[architecture.md](architecture.md); este doc no repite esa tabla de módulos, solo la referencia.

## Módulos (single-module Maven + Spring Modulith)

Un solo `pom.xml`, sin `<modules>` — el "grafo de módulos" real vive a nivel de paquete, verificado
por Spring Modulith (`@ApplicationModule` en cada `package-info.java` + `ApplicationModules.of(...)
.verify()` en `ArquitecturaTest`). Ver la tabla completa en [architecture.md](architecture.md#módulos-spring-modulith).

Cada módulo declara su intención en el Javadoc de su `package-info.java` — vale la pena leerlos
antes de tocar un módulo:

- `orquestacion`: expone `Consultar`, una de las dos puertas que los adaptadores pueden cruzar (la
  del RAG); todo lo demás es `internal`.
- `acciones`: expone `Acciones`, la otra puerta — resumir, sintetizar, preguntas, ideas y traducir
  sobre documentos elegidos a mano. Independiente del RAG: comparte solo el vault indexado y `llm`
  (ver [ADR-0013](adrs/0013-modulo-acciones-independiente-del-rag.md)).
- `recuperacion`: SQL a mano sobre `JdbcClient`, deliberadamente no sobre el `VectorStore` de
  Spring AI — esa abstracción no expresa cuatro señales fusionadas por RRF.
- `llm`: Spring AI se usa solo aquí (chat/streaming/salida estructurada); sus abstracciones de RAG
  quedan fuera a propósito, mismo motivo que `recuperacion`.
- `teams`: protocolo Bot Connector implementado directo, sin SDK — el SDK Java murió en noviembre
  de 2023 y el resto del Bot Framework SDK se archivó en enero de 2026.
- `compartido`: no depende de nadie, sin lógica — solo vocabulario, anidado en
  `compartido/Dominio.java` (los tipos, en [architecture.md](architecture.md#módulos-spring-modulith)).
- `seguridad`: filtro de token Bearer sobre el API programático. Adaptador piel, como `web` y
  `teams` — no depende del núcleo y el núcleo no depende de él.

## JDK target & nivel de lenguaje

- **JDK 25** (`<java.version>25</java.version>`), LTS — Spring Boot 4.1 lo soporta plenamente
  (su línea base es 17).
- Sin mismatch entre el JDK declarado y el del contenedor: el `Dockerfile` usa
  `eclipse-temurin:25` en las tres etapas (deps, build y runtime). Antes de la sincronización con
  `base-conocimiento-sandbox` el `pom` compilaba a 21 dentro de una imagen 25; ya no.

## Dependencias y BOMs

Padre `spring-boot-starter-parent:4.1.0`. Cuatro BOMs importados vía `<dependencyManagement>`, todo
con versión pinneada por `<properties>`, sin rangos:

| BOM | Versión | Motivo |
|---|---|---|
| `spring-modulith-bom` | 2.1.0 | Fronteras entre módulos |
| `spring-ai-bom` | 2.0.0 | Cliente LLM (Ollama + OpenAI-compatible) |
| `testcontainers-bom` | 2.0.5 | Boot 4.1 ya no lo importa solo — hay que declararlo a mano |
| `arconia-bom` | 0.29.0 | Starter de Docling para ingesta de documentos |

**Regla de edición**: una dependencia gestionada por un BOM o `<dependencyManagement>` no debe
llevar su propio `<version>` — es uno de los errores más comunes de un agente al tocar el `pom.xml`.

Dependencias con versión propia fuera de BOM (todas en `<properties>`, sin rangos):
`onnxruntime` 1.28.0, `djl-tokenizers` 0.36.0, `pgvector` 0.1.6, `jgit` 7.7.1, `archunit` 1.4.2,
`jqwik` **1.9.3** (fijado a proposito: 1.10.x imprime una inyección de prompt contra agentes en
cada corrida, ver `pom.xml` y https://lwn.net/Articles/1075317/), `wiremock` 3.13.2.

`./mvnw`/`mvnw.cmd` están commiteados — preferirlos sobre un `mvn` bare.

## Framework: Spring Boot (servidor embebido)

`@SpringBootApplication` (`BaseConocimientoApplication`), sin app-server externo, jar por capas.

## DI / composición

Constructor injection idiomático en `@Service`/`@Component`/`@Repository`. Confirmado con grep
dirigido (`@Autowired`/`@Inject` → 0 resultados en `src/main`; los usos de `@Value` son todos
parámetro de constructor): no hay field injection en código de producción. Sin `@Profile`
condicionales detectados en el árbol principal más allá de la configuración de perfiles de Docker
Compose (no de Spring).

## Fronteras de módulo (Spring Modulith)

Ver la regla completa y sus tres adaptadores en [architecture.md](architecture.md#módulos-spring-modulith)
y el archivo `ArquitecturaTest`. Son 6 pruebas: 5 de ArchUnit (6 `noClasses()` en total, las seis
con `allowEmptyShould(false)` explícito, para que no nazcan verdes por vacías) más
`ApplicationModules.verify()`. Cubren a `web`, `teams` y `seguridad` por
igual, en las dos direcciones, incluyen la frontera lateral entre `seguridad` y los otros dos
adaptadores, y la independencia de `acciones` respecto del RAG y de los adaptadores.
`ApplicationModules.verify()` corre en el mismo ciclo de test que el resto (`make test`), no está
deshabilitado.

## Persistencia

`JdbcClient` sobre PostgreSQL — SQL escrito a mano, no JPA/Hibernate ([ADR pendiente de
formalizar](adrs/) esta decisión; ver `recuperacion/package-info.java` para el razonamiento:
el `VectorStore` de Spring AI no sabe expresar cuatro señales fusionadas por RRF). Migraciones
Flyway en `src/main/resources/db/migration/`, aplicadas al arrancar la app (autoconfig del módulo
`spring-boot-flyway`). Ver [data-model.md](data-model.md) para el esquema completo.

## Configuración y perfiles

`.env`/`.env.example` (31 variables) es la fuente de configuración, consumida por Docker Compose e
inyectada como variables de entorno al contenedor `api`. Un solo `application.yml`, sin
`application-{perfil}.yml`. Las propiedades tipadas son 7 records `@ConfigurationProperties`,
registrados con `@ConfigurationPropertiesScan` en `BaseConocimientoApplication`:

| Record | Prefijo |
|---|---|
| `acciones.AccionesPropiedades` | `kb.acciones` |
| `ingesta.AzureDevOpsPropiedades` | `kb.azdo` |
| `ingesta.GraphPropiedades` | `kb.graph` |
| `orquestacion.UmbralRelevanciaPropiedades` | `kb.orquestacion.umbral-relevancia` |
| `recuperacion.RecuperacionPropiedades` | `kb.recuperacion` |
| `seguridad.SeguridadPropiedades` | `kb` |
| `teams.TeamsPropiedades` | `kb.teams` |

## Build, run, test

Ver los comandos en [AGENTS.md](../AGENTS.md#comandos). `maven-surefire-plugin` incluye
`**/*Test.java`, `**/*Tests.java` y `**/*Properties.java` (este último son las propiedades de
jqwik) — no hay Failsafe/split unit-integration explícito; las pruebas que necesitan Postgres real
usan Testcontainers (`spring-boot-testcontainers` + `testcontainers-postgresql`) dentro del mismo
`test` de Surefire. Frameworks: JUnit 5, AssertJ y Mockito (vía `spring-boot-starter-test`),
ArchUnit, jqwik (property-based), Testcontainers y WireMock, que dobla los HTTP externos: el JWKS y
el conector de Bot Framework, Graph, Azure DevOps y la salud de Ollama. `ArquitecturaTest` corre en
el mismo ciclo y falla el build si se cruza una frontera de módulo.

## Quality gates

| Gate | Estado |
|---|---|
| ArchUnit | **Presente y bloquea** — 6 pruebas en `ArquitecturaTest` (5 de ArchUnit más `ApplicationModules.verify()`), corre en `make test` |
| Checkstyle | **Presente y bloquea** — `failOnViolation=true`, `violationSeverity=error`, incluye las fuentes de test, ligado a `verify`, con `checkstyle-suppressions.xml` |
| Spotless | **Presente y bloquea** — `google-java-format` sobre todo el código, sin `ratchetFrom`; `spotless:check` en `make lint` |
| SpotBugs / PMD | Ausente |
| SonarQube | Ausente |
| Compilador | **Presente y bloquea** — `-Xlint:all -Werror` en `maven-compiler-plugin`: cualquier warning rompe el build |
| Secretos (gitleaks) | **Solo en CI** — `make ci` suma `secrets`; el `lefthook.yml` del monorepo no lo corre en pre-commit |
| Enlaces de la documentación | **Presente y bloquea en CI** — `node scripts/verificar-enlaces.mjs`: un enlace relativo o un ancla de `AGENTS.md`, `COMPONENTS.md`, `README.md` o `docs/` que no resuelve falla el job (#128) |
| CI (`.github/workflows`) | **Presente** — `ci.yml` en la raíz del monorepo (Actions solo lee workflows ahí), `working-directory: base-conocimiento`, corre `make ci` en cada push/PR con JDK 25; detalle en [infrastructure.md](infrastructure.md#cicd) |

Spotless usa `google-java-format` (2 espacios) sin `ratchetFrom`: el formato es uniforme en el
repositorio entero, no solo en lo que cambió. `make format` lo aplica y `make lint` lo verifica
junto con Checkstyle (`checkstyle.xml` + `checkstyle-suppressions.xml`), que cubre lo que Spotless no
formatea (imports no usados, naming, largo de línea).

Formato, estilo, arquitectura y CI bloquean de verdad; lo que sigue sin cubrirse es análisis
estático de bugs (SpotBugs/PMD) y métricas de calidad (SonarQube). Esta tabla la cerró
`/sdlc-ia:instrument-project-java` (etapa F2 de `validacion-workshop/`, en la raíz del monorepo) y
la terminó de endurecer la sincronización con `base-conocimiento-sandbox`.

## Web / API

REST + Server-Sent Events (`ChatController`, sin `springdoc-openapi`/Swagger detectado). Sin
GraphQL ni gRPC. Adaptador adicional no-REST: Bot Connector de Teams (`BotController`).

## Despliegue y empaquetado

Jar por capas habilitado (`spring-boot-maven-plugin` → `<layers><enabled>true</enabled></layers>`)
— reconstruir solo repone la capa de aplicación en `Dockerfile`. Sin GraalVM native-image. Sin
empaquetado WAR.

## Transversales

Observabilidad: `spring-boot-starter-actuator` expone `health,info,metrics`
(`management.endpoints.web.exposure.include` en `application.yml`), sin acotar por perfil (ver
[infrastructure.md](infrastructure.md#observabilidad)). Sin `resilience4j`/Spring Retry ni
mensajería (Kafka/RabbitMQ/JMS) detectados. Señal de IA: `spring-ai-*` (ver arriba) y este mismo
repo trae `instrumentacion-java-ia/` como módulo hermano del monorepo. `.mcp.json` y los hooks del
agente viven en la raíz del monorepo, no en `base-conocimiento/`: ver
[infrastructure.md](infrastructure.md#agente-de-ia-hooks-y-mcp).

## Reglas reforzadas al editar (hooks del agente)

Desde `/sdlc-ia:instrument-agent-java` (ver [infrastructure.md](infrastructure.md#agente-de-ia-hooks-y-mcp)), dos de las reglas de
este documento ya no dependen solo de que alguien las lea:

- **Versiones centralizadas** (`dependencyManagement`/BOMs arriba): un hook avisa si una edición
  a `pom.xml` agrega una `<dependency>` con `<version>` literal en vez de un `${property}`.
- **Migraciones de Flyway inmutables**: un hook bloquea editar un archivo ya existente bajo
  `src/main/resources/db/migration/` — crear el siguiente `V<n>__...sql` sigue permitido.

## Gotchas / hotspots

- **`spring-boot-flyway` como módulo aparte de `flyway-core`** en Boot 4 — fácil de omitir al
  copiar dependencias de un proyecto Boot 3, y el fallo es silencioso (arranca sin migrar).
- **Testcontainers 2.0 renombró sus módulos** (`postgresql` → `testcontainers-postgresql`) — un
  agente que copie una dependencia vieja de otro repo se rompe en tiempo de ejecución, no de
  compilación.
