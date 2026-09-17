# Infraestructura — Base de Conocimiento

## Desarrollo local

### Prerrequisitos

- Docker + Docker Compose (el `Makefile` orquesta todo sobre `compose.yml` y sus overrides).
- JDK 25 (el wrapper `./mvnw` viene commiteado, no hace falta Maven instalado). Con un JDK anterior
  el build falla con `release version 25 not supported`; `make jdk-check` lo dice antes de compilar.
- Opcional: GPU NVIDIA (`nvidia-smi`). `make up` no solo la detecta: lee VRAM, Compute Capability y
  versión del driver, y reparte en consecuencia — LLM siempre en la tarjeta, embeddings desde 6 GB,
  docling desde 8 GB. `make gpu-check` explica qué decidió y por qué; `KB_GPU`, `KB_DOCLING_GPU` y
  los dos umbrales lo fuerzan. Detalle en el [README](../README.md#reparto-de-la-gpu).
- En Windows, `make` necesita Git for Windows instalado: el `Makefile` usa su `sh.exe` como shell
  porque las recetas son POSIX. Funciona igual desde PowerShell y desde Git Bash.

El LLM es intercambiable por perfil (`make up-bonsai`, `up-ministral`, `up-qwen35`, …): son
overrides de compose encadenados sobre `compose.yml`. La tabla completa de los siete perfiles, con
su descarga y su modelo, está en el [README](../README.md#perfiles-de-modelo).

### Inicio rápido

```bash
cp .env.example .env   # completar variables, ver abajo
make pull-models       # descarga LLM, embeddings y reranker a KB_DATA_DIR (~5.5 GB, una vez)
make up                # levanta db, ollama, docling-serve y api
make health            # confirma que los 4 servicios responden
make ingest            # ingiere el corpus de ejemplo (vault/documentos)
```

### Servicios (local)

| Servicio | Imagen | Propósito |
|---|---|---|
| `db` | `pgvector/pgvector:pg18-trixie` | Postgres 18 + pgvector: tabla única de embeddings, FTS, cola, auditoría |
| `ollama` | `ollama/ollama` | `gemma3:4b` (planner/destilación/síntesis) y `bge-m3` (embeddings) |
| `docling-serve` | `quay.io/docling-project/docling-serve-cpu` | Extrae PDF/DOCX/PPTX a Markdown |
| `api` | build propio (Java, jar por capas) | Ingesta, retrieval, orquestación, UI estática, endpoint de Teams |

### Variables de entorno

- `.env.example` es la lista canónica (31 variables): puertos, credenciales de Postgres, modelo
  LLM/embeddings activos, flags de las fuentes opcionales (`KB_TEAMS_HABILITADO`,
  `KB_GRAPH_HABILITADO`, `KB_AZDO_HABILITADO`) y sus credenciales asociadas.
- Nunca commitees `.env`.

## Producción

### Objetivo de despliegue

Docker Compose en una VM/máquina propia — `make up` con el override de GPU si el host la tiene
(`compose.gpu.yml`); no hay manifiestos de Kubernetes en el repo.

**Secretos:** el mismo archivo `.env` que lee Docker Compose, puesto a mano en el host. No hay
gestor de secretos; `.env.example` sigue siendo la lista de lo que ese archivo debe traer.

### Topología

<!-- TODO: describir qué máquina/VM concreta corre esto hoy y si hay algo delante (reverse proxy,
TLS terminator). No está en el repo — es conocimiento operativo del equipo. -->

### CI/CD

- **Herramienta:** GitHub Actions. `.github/workflows/ci.yml` vive en la **raíz del monorepo**, no
  en `base-conocimiento/` (Actions solo lee workflows ahí). El job corre con
  `working-directory: base-conocimiento` por defecto, y los dos pasos que cubren el monorepo entero
  —el sensor de enlaces y el del playbook— lo sobrescriben con `working-directory: .`.
- **Trigger:** toda PR, más cada push a `dev` y `main`. Un push a una rama sin PR no corre CI: ahí
  avisa el pre-push local (`make check`). Un segundo push al mismo ref cancela la corrida anterior.
  El job `check` no tiene `if`: un job saltado por un condicional cuenta como exitoso para un check
  requerido, y la copia saltada podía tapar un rojo sobre el mismo commit (#132).
- **Pasos (job `check`):** instala gitleaks 8.30.1 (con verificación de checksum) y JDK 25, corre
  `make ci` (lint, build, pruebas y escaneo de secretos), el sensor de enlaces de todo el monorepo
  —desde #144 también corre en el pre-push, antes que `make check`— y publica los reportes de
  Surefire como artefacto. El mismo workflow verifica además el playbook;
  el CHANGELOG del plugin lo verifica un workflow aparte, `liberacion.yml`, solo en las PR hacia
  `main`.
- **CD:** no hay. El camino a producción sigue siendo **manual**: `make up` a mano cuando hace falta.

## Agente de IA (hooks y MCP)

Exclusivo de Claude Code: ningún otro agente de IA lee hoy estos archivos, y los dos viven en la
**raíz del monorepo**, no en `base-conocimiento/`.

### Hooks

Instalados por `/sdlc-ia:instrument-agent-java` en `scripts/agent-hooks/` (bash puro) y
registrados en `.claude/settings.json`: 8 scripts.

| Hook | Bloquea | Qué hace |
|---|---|---|
| Secret read-guard | Sí | Antes de `Bash`, `PowerShell` y `Read`, deniega leer `.env`, claves privadas, `secrets.json`, etc. No cubre `@`-referencias ni Grep/Glob. |
| Format on edit | No | Corre Spotless acotado al `.java` que se acaba de editar (`-DspotlessFiles`, ~5s). |
| Bloqueo de comandos peligrosos | Sí | Dos scripts, uno para `Bash` y otro para `PowerShell`: `rm -rf` fuera del repo, `sudo`, force-push a `main`/`dev`, `git reset --hard`, `mvn deploy`. No es un sandbox: texto, no un parser de shell. |
| Dependency sweep | No | Al iniciar o reanudar sesión, `mvn versions:display-dependency-updates`. **~60s en frío, ~5s con caché tibio** — si se vuelve lento seguido, sacarlo de SessionStart. |
| Audit log | No | Registra el `tool_input` completo de cada llamada en `logs/audit.log` (gitignored). Puede contener cualquier cosa que haya pasado por una herramienta. |
| Version-pin guard | Avisa | Tras editar `pom.xml`, avisa si una dependencia nueva trae `<version>` literal en vez de heredarla de `<dependencyManagement>`. |
| Generated-files guard | Sí | Deniega editar una migración de Flyway ya existente bajo `db/migration/`; crear la siguiente sigue permitido. |

### MCP

`.mcp.json` está committeado. Cada máquina lo aprueba una vez: correr `claude` en el repo, aceptar
el diálogo de confianza del workspace y confirmar cada servidor con `/mcp`.

| Servidor | Da acceso a | Variable de entorno |
|---|---|---|
| GitHub (HTTP, `Authorization: Bearer ${GITHUB_PAT}`) | Issues, Pull Requests, runs de Actions | `GITHUB_PAT` |
| DBHub (stdio, `npx @bytebase/dbhub@1.2.1 --dsn ${APP_DSN}`) | Lectura **y escritura** sobre la base Postgres real: DBHub ya no soporta `--readonly` | `APP_DSN` (ej. `postgres://kb:kb@localhost:5432/baseconocimiento?sslmode=disable`) |

Las dos variables se exportan en el entorno de quien use el agente; nunca se escriben literales en
el archivo. Context7 no se instaló; se puede sumar con `/sdlc-ia:instrument-agent-java`.

## Observabilidad

`spring-boot-starter-actuator` está en el classpath y sus endpoints están expuestos
(`management.endpoints.web.exposure.include: health,info,metrics`, `application.yml:101-108`), sin
acotar por perfil — el mismo `application.yml` corre en local y en producción. Nada los consume
todavía: no hay Prometheus, Grafana ni Micrometer configurado en el repo. Logs: `make logs` sigue
el log del contenedor `api`; no hay agregador centralizado configurado.

## Docs relacionados

- [Arquitectura](./architecture.md)
- [Decisiones](./adrs/)
