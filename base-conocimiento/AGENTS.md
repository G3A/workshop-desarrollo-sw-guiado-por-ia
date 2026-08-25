# AGENTS.md — Base de Conocimiento

RAG interno con citas verificables sobre documentos, código, canales de Teams y work items — una
sola tabla de embeddings, 100% local, costo cero.

Guía para agentes de IA que trabajan en este repositorio. Sigue la convención [agents.md](https://agents.md).

Este archivo solo captura lo que no es obvio leyendo el código. Para arquitectura, modelo de datos, decisiones y contexto más amplio, sigue los enlaces y lee la fuente.

## Dónde encontrar las cosas

- [Arquitectura](docs/architecture.md) — contenedores, módulos Spring Modulith, pipeline de 7 etapas.
- [Contexto de negocio](docs/business.md) — qué es esto y para quién.
- [Modelo de datos](docs/data-model.md) — la tabla única de embeddings y el resto del esquema.
- [Infraestructura](docs/infrastructure.md) — desarrollo local, despliegue, variables de entorno.
- [Java — profundidad técnica](docs/java.md) — módulos, JDK, DI, persistencia, quality gates, CI.
- [Usuario objetivo](docs/target-user.md) — quién usa el sistema y qué le importa.
- [Diseño](docs/design.md) — el adaptador web (HTML/JS sin build).
- [Decisiones (ADRs)](docs/adrs/) — 12 decisiones registradas, desde la tabla única de embeddings hasta Spring Modulith.
- [Plan del proyecto, fase por fase](docs/plans/plan-base-conocimiento.md) — historia de cómo se llegó al estado actual.
- [Investigación VRAM/modelo LLM](docs/investigacion-vram-y-modelo-llm.md) — por qué Gemma3:4b y el trade-off GPU/CPU.
- [Registro del bot de Teams](docs/teams/registro-azure-bot.md) — cómo registrar el bot en Azure.

Lee estos docs antes de hacer cambios estructurales.

## Comandos

```bash
make up            # levanta los 4 servicios (db, ollama, docling-serve, api); detecta GPU sola
make health        # reporte de salud: db, ollama, modelos faltantes
make ingest        # ingiere vault/documentos (corpus de ejemplo)
make check         # lint + build + test -- la señal de confianza local antes de un commit
make ci            # lo mismo que corre CI (suma el escaneo de secretos)
make format         # aplica Spotless (google-java-format) a lo que cambió desde `dev`
make hooks          # instala los git hooks de Lefthook (correr una vez, desde la raíz del monorepo)
make psql          # abre una sesión psql contra la base
```

`make help` lista los ~25 targets restantes (perfiles por modelo LLM, ingesta de repos/Teams/Azure
DevOps, descarga de modelos). Preferí siempre estos targets sobre invocar `./mvnw`/`docker compose`
a mano — el `Makefile` ya resuelve flags de perfil y detección de GPU.

## Reglas no obvias

- **Los adaptadores son piel**: `web` y `teams` solo pueden depender de la fachada
  `orquestacion.Consultar` y de `compartido`. Nunca de `recuperacion`, `ingesta`, `modelos` ni
  `llm` — lo hace cumplir `ArquitecturaTest` (ArchUnit + `ApplicationModules.verify()`) en cada
  build, no es solo una convención escrita.
- **El texto crudo nunca se embebe**: el embedding ancla en los campos que destila el LLM
  (`searchable_question`, `summary`, `resolution`); el texto crudo solo alimenta full-text search.
  Ver [ADR-0003](docs/adrs/0003-no-embeber-texto-crudo.md).
- **`spring-boot-flyway` (autoconfig) es un módulo aparte de `flyway-core`** en Spring Boot 4 — si
  falta, la app arranca contra una base vacía sin protestar, sin correr ninguna migración.
- **`lefthook.yml` vive en la raíz del monorepo, no en `base-conocimiento/`** — lefthook busca su
  config en la raíz del repositorio git, que aquí es un nivel arriba. Sus comandos usan `root:
  "base-conocimiento/"` para acotarse a este proyecto. `make hooks` debe correrse desde la raíz del
  monorepo, no desde acá.
- **Las recetas del `Makefile` que llaman a Maven usan `sh ./mvnw`, no `./mvnw` a secas** — en esta
  máquina, `make` (el `ezwinports.make` que se instala vía winget en Windows) ejecuta una receta
  que empieza con `./algo` en forma directa, sin pasar por el shell configurado, y `./` no se
  resuelve así. `sh ./mvnw` fuerza el paso por el intérprete correcto.
- `spotless-maven-plugin` usa `ratchetFrom dev` (rama local), no `origin/dev`, mientras el reorg a
  monorepo no se haya empujado — actualizar la referencia una vez que `dev` esté en el remoto.

## CI y quality gates

GitHub Actions (`.github/workflows/ci.yml`) corre `make ci` en cada push/PR. `-Werror` (`-Xlint:all,
-processing`) y ArchUnit bloquean de verdad; Checkstyle reporta pero no bloquea todavía
(`failOnViolation=false`, 35 violaciones preexistentes de brownfield, sobre todo `ConstantName`
en el logger `log`); el escaneo de secretos (`gitleaks`) corre solo en CI, no en pre-commit local.
Detalle completo de qué se instaló, qué se rompió para verificar, y qué se dejó pendiente en
`validacion-workshop/f2-preparar-proyecto.md`, en la raíz del monorepo.

## Pruebas

JUnit 5 vía Surefire (`**/*Test.java`, `**/*Tests.java`, `**/*Properties.java` — el último son las
propiedades de jqwik). Un solo comando corre todo: `make test` (equivalente a `./mvnw test`), sin
split unit/integration explícito — las pruebas que necesitan Postgres real usan Testcontainers
(`spring-boot-testcontainers` + `testcontainers-postgresql`), WireMock dobla el JWKS de Bot
Framework, y jqwik aporta property-based testing. `ArquitecturaTest` corre en el mismo ciclo y
falla el build si se cruza una frontera de módulo.

## Estilo de código

Spotless con `google-java-format` (2 espacios), acotado con `ratchetFrom` a lo que cambió desde
`dev` — el código preexistente no se reformatea solo. Checkstyle (`checkstyle.xml`) cubre lo que
Spotless no formatea (imports no usados, naming, largo de línea); hoy solo reporta
(`make lint`), no bloquea — hay 35 violaciones de brownfield sin triage, ver
`validacion-workshop/f2-preparar-proyecto.md`.

## Seguridad

- No commitees `.env` ni archivos con credenciales. Agrega variables nuevas a `.env.example`.
- No registres secretos, tokens ni información personal en logs.
- Asume que cualquier cosa en este repo es legible por un agente de IA — nunca pegues secretos aquí.

## Hooks del agente

Instalados por `/sdlc-ia:instrument-agent-java` en `scripts/agent-hooks/` (bash puro), registrados
en `.claude/settings.json` **en la raíz del monorepo** — esto es exclusivo de Claude Code, ningún
otro agente de IA los lee hoy. Los 7 verificados en vivo, rompiéndolos de verdad:

| Hook | Bloquea | Qué hace |
|---|---|---|
| Secret read-guard | Sí | Deniega leer `.env`, claves privadas, `secrets.json`, etc. No cubre `@`-referencias ni Grep/Glob. |
| Format on edit | No | Corre Spotless acotado al `.java` que se acaba de editar (`-DspotlessFiles`, ~5s). |
| Bloqueo de comandos peligrosos | Sí | `rm -rf` fuera del repo, `sudo`, force-push a `main`/`dev`, `git reset --hard`, `mvn deploy`. No es un sandbox: texto, no un parser de shell. |
| Dependency sweep | No | Al iniciar sesión, `mvn versions:display-dependency-updates` (no hay `make audit` ni dependency-check-maven). **~60s en frío, ~5s con caché tibio** — si esto se vuelve lento seguido, sacarlo de SessionStart. |
| Audit log | No | Registra `tool_input` completo de cada llamada en `logs/audit.log` (gitignored). Puede contener cualquier cosa que haya pasado por una herramienta. |
| Version-pin guard | Avisa | Tras editar `pom.xml`, avisa si una dependencia nueva trae `<version>` literal en vez de heredarla de `<dependencyManagement>`. |
| Generated-files guard | Sí | Deniega editar una migración de Flyway ya existente bajo `db/migration/`; crear la siguiente sigue permitido. Sin rama Liquibase (no se usa aquí). |

## MCP (Model Context Protocol)

`.mcp.json` en la raíz del monorepo, **committeado, escrito, pendiente de aprobación** — correr
`claude` en este repo y aceptar el diálogo de confianza del workspace, luego `/mcp` para confirmar
cada servidor.

| Servidor | Da acceso a | Variable de entorno |
|---|---|---|
| GitHub | Issues, Pull Requests, runs de Actions | `GITHUB_PAT` |
| DBHub (`@bytebase/dbhub@1.2.1`) | Lectura **y escritura** (el flag `--readonly` ya no existe en DBHub) sobre la base Postgres real | `APP_DSN` (ej. `postgres://kb:kb@localhost:5432/baseconocimiento?sslmode=disable`) |

Context7 no se instaló (decisión explícita en esta pasada, se puede sumar después con
`/sdlc-ia:instrument-agent-java`).
