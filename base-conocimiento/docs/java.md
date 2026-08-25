# Java — Base de Conocimiento

Profundidad técnica del stack Java/Spring. Para el panorama general ver
[architecture.md](architecture.md); este doc no repite esa tabla de módulos, solo la referencia.

## Módulos (single-module Maven + Spring Modulith)

Un solo `pom.xml`, sin `<modules>` — el "grafo de módulos" real vive a nivel de paquete, verificado
por Spring Modulith (`@ApplicationModule` en cada `package-info.java` + `ApplicationModules.of(...)
.verify()` en `ArquitecturaTest`). Ver la tabla completa en [architecture.md](architecture.md#módulos-spring-modulith).

Cada módulo declara su intención en el Javadoc de su `package-info.java` — vale la pena leerlos
antes de tocar un módulo:

- `orquestacion`: expone `Consultar`, la única puerta que los adaptadores pueden cruzar; todo lo
  demás es `internal`.
- `recuperacion`: SQL a mano sobre `JdbcClient`, deliberadamente no sobre el `VectorStore` de
  Spring AI — esa abstracción no expresa cuatro señales fusionadas por RRF.
- `llm`: Spring AI se usa solo aquí (chat/streaming/salida estructurada); sus abstracciones de RAG
  quedan fuera a propósito, mismo motivo que `recuperacion`.
- `teams`: protocolo Bot Connector implementado directo, sin SDK — el SDK Java murió en noviembre
  de 2023 y el resto del Bot Framework SDK se archivó en enero de 2026.
- `compartido`: no depende de nadie, sin lógica — solo vocabulario (`Cita`, `Fragmento`,
  `Proyecto`, `Respuesta`).
- `seguridad`: filtro de token Bearer sobre el API programático. Adaptador piel, como `web` y
  `teams` — no depende del núcleo y el núcleo no depende de él.

## JDK target & nivel de lenguaje

- **JDK 21** (`<java.version>21</java.version>`), LTS — el comentario del `pom.xml` aclara que es
  lo instalado en la máquina de desarrollo y que Spring Boot 4.1 soporta desde la línea base 17;
  subir a 25 es cambiar esa propiedad más los tags del `Dockerfile`.
- Sin mismatch detectado entre el JDK declarado y el usado en Docker (`Dockerfile` no inspeccionado
  línea a línea en esta pasada — <!-- TODO: confirmar el tag base de eclipse-temurin del Dockerfile
  coincide con 21 -->).

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
`jqwik` 1.10.1, `wiremock` 3.13.2.

`./mvnw`/`mvnw.cmd` están commiteados — preferirlos sobre un `mvn` bare.

## Framework: Spring Boot (servidor embebido)

`@SpringBootApplication` (`BaseConocimientoApplication`), sin app-server externo, jar por capas.

## DI / composición

Constructor injection idiomático en `@Service`/`@Component`/`@Repository`. No se detectó field
injection en la pasada de discovery de esta skill (confirmar con Grep dirigido si aparece uno
nuevo). Sin `@Profile` condicionales detectados en el árbol principal más allá de la configuración
de perfiles de Docker Compose (no de Spring).

## Fronteras de módulo (Spring Modulith)

Ver la regla completa y sus tres adaptadores en [architecture.md](architecture.md#módulos-spring-modulith)
y el archivo `ArquitecturaTest`. Las 4 reglas (3 `noClasses()` de ArchUnit + `ApplicationModules.verify()`)
corren activadas de verdad, sin `allowEmptyShould`, y cubren a `web`, `teams` y `seguridad` por igual.
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
inyectada como variables de entorno al contenedor `api` — no se detectaron `application-{perfil}.yml`
múltiples ni `@ConfigurationProperties` explorados en esta pasada
(<!-- TODO: listar las clases `@ConfigurationProperties` reales, ej. las que ya se ven en
`SeguridadPropiedades`, `RecuperacionPropiedades`, `TeamsPropiedades`, `UmbralRelevanciaPropiedades` -->).

## Build, run, test

Ver los comandos en [AGENTS.md](../AGENTS.md#comandos). `maven-surefire-plugin` incluye
`**/*Test.java`, `**/*Tests.java` y `**/*Properties.java` (este último son las propiedades de
jqwik) — no hay Failsafe/split unit-integration explícito; las pruebas que necesitan Postgres real
usan Testcontainers dentro del mismo `test` de Surefire. Frameworks: JUnit 5, AssertJ (vía
`spring-boot-starter-test`), ArchUnit, jqwik (property-based), Testcontainers, WireMock (dobla el
JWKS de Bot Framework).

## Quality gates

| Gate | Estado |
|---|---|
| ArchUnit | **Presente** — 4 reglas, `ArquitecturaTest`, corre en `make test` |
| Checkstyle | Ausente |
| Spotless | Ausente |
| SpotBugs / PMD | Ausente |
| SonarQube | Ausente |
| CI (`.github/workflows`) | Ausente |

Solo ArchUnit está en su lugar hoy — no asumas cobertura de formato/estilo. Cerrar esta tabla es
justo lo que corre `/sdlc-ia:instrument-project-java` (validado en la etapa F2 de
`validacion-workshop/` en la raíz del monorepo).

## Web / API

REST + Server-Sent Events (`ChatController`, sin `springdoc-openapi`/Swagger detectado). Sin
GraphQL ni gRPC. Adaptador adicional no-REST: Bot Connector de Teams (`BotController`).

## Despliegue y empaquetado

Jar por capas habilitado (`spring-boot-maven-plugin` → `<layers><enabled>true</enabled></layers>`)
— reconstruir solo repone la capa de aplicación en `Dockerfile`. Sin GraalVM native-image. Sin
empaquetado WAR.

## Transversales

Observabilidad: `spring-boot-starter-actuator` en el classpath, exposición real no confirmada (ver
[infrastructure.md](infrastructure.md)). Sin `resilience4j`/Spring Retry ni mensajería
(Kafka/RabbitMQ/JMS) detectados. Señal de IA: `spring-ai-*` (ver arriba) y este mismo repo trae
`instrumentacion-java-ia/` como módulo hermano del monorepo — no hay `.mcp.json` en
`base-conocimiento/` todavía (llega en la etapa F2 de la validación, vía
`/sdlc-ia:instrument-agent-java`).

## Reglas reforzadas al editar (hooks del agente)

Desde `/sdlc-ia:instrument-agent-java` (ver `AGENTS.md#hooks-del-agente`), dos de las reglas de
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
