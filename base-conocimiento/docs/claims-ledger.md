# Registro de afirmaciones

Generado originalmente por la skill `agent-context-java`. Registra las afirmaciones factuales clave
de la documentación, su fuente en el repositorio y si fueron confirmadas por una persona. Vuelve a
ejecutar la skill para regenerarlo.

**Última verificación a mano: 2026-09-15** (issue #137), al volver a correr
`/sdlc-ia:agent-context-java` en modo aumento: tres frases de los docs contradecían el código. La
anterior fue la del 2026-09-14 (issue #120). La del 2026-08-31, después de la sincronización con
`base-conocimiento-sandbox`, invalidó cinco afirmaciones de este registro; la de #120 invalidó una
más y encontró cinco frases de los docs que contradecían filas vigentes. Lo que dejó de ser cierto
se marca en vez de borrarse, porque saber qué dejó de valer vale tanto como saber qué vale.

## Vigentes

| Afirmación | Fuente | Confianza | Estado |
|---|---|---|---|
| El proyecto apunta a JDK 25 sobre Spring Boot 4.1.0. | `pom.xml` (`<java.version>`) | alta | confirmada |
| El JDK del `Dockerfile` coincide con el declarado en `pom.xml`: ambos 25. | `pom.xml` (`<java.version>25`), `Dockerfile` (`eclipse-temurin:25` declarado en `deps` y `runtime`, heredado por `build` y `layers`) | alta | confirmada (2026-09-15: son cuatro etapas, no tres) |
| La persistencia es `JdbcClient` a mano sobre Postgres, no JPA/Hibernate. | `pom.xml` (sin `data-jpa` ni `hibernate-core`), `recuperacion/package-info.java` | alta | confirmada |
| Las migraciones de Flyway corren al arrancar la app (autoconfig de `spring-boot-flyway`). | `pom.xml` (`spring-boot-flyway`) | alta | confirmada |
| `spring-boot-starter-actuator` expone `health`/`info`/`metrics`, sin acotar por perfil. | `application.yml` (`include: health,info,metrics`) | alta | confirmada → issue #5, TODO resuelto en `infrastructure.md` |
| No hay field injection en `src/main`. | `grep -rn "@Autowired\|@Inject" src/main` → 0 resultados; los usos de `@Value` son todos parámetro de constructor | alta | confirmada → issue #7 |
| Formato, estilo, arquitectura y CI bloquean el build; SpotBugs/PMD y SonarQube siguen ausentes. | `pom.xml` (Spotless, Checkstyle `failOnViolation=true`), `.github/workflows/ci.yml` en la raíz del monorepo | alta | confirmada (2026-08-31) |
| `ArquitecturaTest` tiene 6 pruebas: 5 de ArchUnit (6 `noClasses()`, las seis con `allowEmptyShould(false)`) más `ApplicationModules.verify()`. | `ArquitecturaTest.java` | alta | confirmada (2026-09-14) |
| `compartido` son 7 tipos anidados en `Dominio`: `ProyectoId`, `Pregunta`, `IdiomaRespuesta`, `Filtros`, `Fragmento`, `Cita`, `Respuesta`. No existe `Proyecto`. | `compartido/Dominio.java` | alta | confirmada (2026-09-14) |
| Hay 7 records `@ConfigurationProperties`, registrados con `@ConfigurationPropertiesScan`; un solo `application.yml`, sin `application-{perfil}.yml`. | `BaseConocimientoApplication.java`, `grep -rl "@ConfigurationProperties(" src/main`, `src/main/resources/` | alta | confirmada (2026-09-14) |
| `.mcp.json` (GitHub y DBHub) y los hooks del agente (8 scripts en `scripts/agent-hooks/`, registrados en `.claude/settings.json`) viven en la raíz del monorepo. | `.mcp.json`, `.claude/settings.json` en la raíz | alta | confirmada (2026-09-14) |
| `seguridad` está cubierto en las dos direcciones y tiene además su frontera lateral con `web`/`teams`. | `ArquitecturaTest.seguridadNoSeMezclaConLosOtrosAdaptadores` | alta | confirmada (2026-08-31) |
| `jqwik` está fijado en 1.9.3 a propósito: 1.10.x imprime una inyección de prompt contra agentes en cada corrida. | `pom.xml` (comentario de `<jqwik.version>`), <https://lwn.net/Articles/1075317/> | alta | confirmada (2026-08-31) |
| El `Makefile` fija su propio `SHELL` en Windows (el `sh.exe` de Git for Windows) y le antepone su directorio al `PATH` cuando el `PATH` viene en formato Windows. | `Makefile` (bloque `ifeq ($(OS),Windows_NT)`) | alta | confirmada (2026-08-31) — sin eso, `make` desde PowerShell cae a `cmd.exe` y casi ninguna receta funciona |
| El reparto de la GPU se deriva de `nvidia-smi` (VRAM, Compute Capability, driver), no de constantes. | `Makefile` (`GPU_PLAN`), `make gpu-check` | alta | confirmada (2026-08-31) |
| `docling-serve` no libera la VRAM entre conversiones y `GET /v1/clear/converters` no la recupera; solo reiniciar el proceso. | Medido: 2053 MiB antes y después del endpoint; sesión 27 de `investigacion-vram-y-modelo-llm.md` | alta | confirmada (2026-08-31) |
| Hay 10 `compose.*.yml` de perfil de modelo; 7 tienen target `up-`/`down-`/`pull-`. | `ls compose.*.yml`, `grep "^up-" Makefile` | alta | confirmada (2026-08-31) |
| `V1__esquema.sql` crea 6 tablas (`sources`, `documents`, `chunks`, `term_stats`, `ingest_jobs`, `query_log`); V2 a V5 crean una cada una y V6 no crea tablas: agrega `query_log_id` a `streams_en_curso`. | `src/main/resources/db/migration/` (`CREATE TABLE`) | alta | confirmada (2026-09-15) |
| En producción los secretos salen del mismo archivo `.env` que lee Docker Compose, puesto a mano en el host; no hay gestor de secretos. | usuario | alta | confirmada (2026-09-15) |
| `REVIEW.md` y la plantilla de PR viven en la raíz del monorepo: el servicio de Code Review solo lee `REVIEW.md` en la raíz del repositorio git, y GitHub la plantilla en `.github/` de la raíz. | <https://code.claude.com/docs/en/code-review> (sección REVIEW.md) | alta | confirmada (2026-09-15) |
| El despliegue es Docker Compose en una VM/máquina propia y el paso a producción sigue siendo manual. | usuario | alta | matizada (2026-08-31): hay CI (build, test, lint, secretos en cada push/PR), pero **no** hay CD |

## Invalidadas por cambios posteriores

Se conservan porque documentan el estado del repositorio cuando se escribieron, y porque un agente
que las encuentre citadas en otro documento necesita saber que ya no valen.

| Afirmación (ya no vigente) | Qué la invalidó |
|---|---|
| «ArchUnit es el único quality gate presente; no hay Checkstyle, Spotless, SonarQube ni CI.» | La sincronización con `base-conocimiento-sandbox` trajo Spotless sobre todo el código y Checkstyle bloqueando, y el CI ya existía en la raíz del monorepo. SonarQube y SpotBugs/PMD siguen ausentes. |
| «Las reglas `losAdaptadoresNoConocenElNucleo` y `elNucleoNoConoceALosAdaptadores` tienen `allowEmptyShould(true)` con un comentario desactualizado.» | Hoy usan `allowEmptyShould(false)` explícito. `compartidoEsHoja` conservó `true` hasta #120, que la pasó a `false`: con `true`, renombrar el paquete la dejaba verde sin revisar nada. |
| «`ArquitecturaTest` tiene 5 pruebas: 4 de ArchUnit (5 `noClasses()`) más `ApplicationModules.verify()`.» | Se sumó `accionesNoConoceElRagNiLosAdaptadores` (issue #38): son 6 pruebas, 5 de ArchUnit y 6 `noClasses()`. |
| «El paquete `seguridad` no está cubierto por ninguna de las 4 reglas de `ArquitecturaTest`.» | La fusión de las dos versiones del test lo incorporó a las reglas de adaptadores y núcleo, y le agregó `seguridadNoSeMezclaConLosOtrosAdaptadores`. |
| «El paquete `seguridad` no tiene `@ApplicationModule` ni Javadoc en su `package-info.java`.» | `seguridad/package-info.java` existe, con `@ApplicationModule(displayName = "Seguridad")` y su Javadoc. |
| «El camino a producción es manual, **sin CI**.» | El CI existe y bloquea; lo que no hay es despliegue continuo. Ver la fila matizada arriba. |

## Docs que contradecían filas vigentes

Encontradas en #120 y en #137. El registro estaba bien; los docs no. Lección: una fila confirmada
aquí no corrige sola la frase vieja de otro documento, así que al confirmar una fila hay que buscar
la afirmación contraria en `docs/` y en `AGENTS.md`.

| Frase del doc (ya corregida) | Contradecía |
|---|---|
| `infrastructure.md`: «ninguna todavía — no existe `.github/workflows/` en el repo» | La fila del CI: `ci.yml` en la raíz del monorepo corre `make ci` en cada push; lo que no hay es CD. |
| `java.md`: actuator con «exposición real no confirmada» | La fila de actuator: `include: health,info,metrics` en `application.yml`. |
| `java.md`: «no hay `.mcp.json` en `base-conocimiento/` todavía» | `.mcp.json` está en la raíz del monorepo desde la etapa F2. |
| `java.md` y `architecture.md`: `compartido` = `Cita`, `Fragmento`, `Proyecto`, `Respuesta` | `compartido/Dominio.java`: 7 tipos, sin `Proyecto`. |
| `java.md`: TODO «listar las clases `@ConfigurationProperties`» | Las 7 existían, registradas con `@ConfigurationPropertiesScan`. |
| `data-model.md`: «no es un paso explícito de CI — no hay CI todavía» (2026-09-15) | La fila del CI. |
| `architecture.md`: «Cuatro tablas más la cola (`V1__esquema.sql`)» (2026-09-15) | La fila de las migraciones: V1 crea 6 tablas; la frase omitía `term_stats`. |
| `java.md`: `eclipse-temurin:25` «en las tres etapas (deps, build y runtime)» (2026-09-15) | El `Dockerfile` tiene cuatro: faltaba `layers`. El JDK era correcto. |
