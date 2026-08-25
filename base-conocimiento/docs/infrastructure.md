# Infraestructura — Base de Conocimiento

## Desarrollo local

### Prerrequisitos

- Docker + Docker Compose (el `Makefile` orquesta todo sobre `compose.yml` y sus overrides).
- JDK 21 (el wrapper `./mvnw` viene commiteado, no hace falta Maven instalado).
- Opcional: GPU NVIDIA (`nvidia-smi`) — `make up` la detecta sola y aplica `compose.gpu.yml`.

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

### Topología

<!-- TODO: describir qué máquina/VM concreta corre esto hoy y si hay algo delante (reverse proxy,
TLS terminator). No está en el repo — es conocimiento operativo del equipo. -->

### CI/CD

- **Herramienta:** ninguna todavía — no existe `.github/workflows/` en el repo.
- **Trigger:** —
- **Pasos:** el camino a producción hoy es **manual**: `docker compose up` / `make up` a mano
  cuando hace falta. Cerrar esta brecha es exactamente lo que instala
  `/sdlc-ia:instrument-project-java` (control 8, CI) — ver la validación de F2 en
  `validacion-workshop/` en la raíz del monorepo.

## Agente de IA (MCP)

`.mcp.json` (raíz del monorepo, committeado): GitHub vía HTTP con `Authorization: Bearer
${GITHUB_PAT}`; DBHub vía stdio (`npx @bytebase/dbhub@1.2.1`) con `--dsn ${APP_DSN}` apuntando a la
base Postgres real de este proyecto (`jdbc:postgresql://localhost:5432/baseconocimiento`) — DBHub
ya no soporta `--readonly`, así que hoy da lectura y escritura sobre esa base. Ambas variables se
exportan en el entorno de quien use el agente, nunca se escriben literales en el archivo.

## Observabilidad

`spring-boot-starter-actuator` está en el classpath, pero no hay evidencia en el repo de que sus
endpoints estén expuestos con un perfil o de que algo (Prometheus, Grafana) los consuma —
<!-- TODO: confirmar si Actuator está expuesto en producción y a dónde van sus métricas. --> Logs:
`make logs` sigue el log del contenedor `api`; no hay agregador centralizado configurado.

## Docs relacionados

- [Arquitectura](./architecture.md)
- [Decisiones](./adrs/)
