# Arquitectura

Referencia del estado actual del sistema. Para la historia de cómo se llegó hasta acá — fase por
fase, con los hallazgos reales de cada una — ver
[`docs/plans/plan-base-conocimiento.md`](plans/plan-base-conocimiento.md). Para el porqué de una
decisión puntual, ver [`docs/adrs/`](adrs).

## Contenedores

| Servicio | Imagen | Rol |
|---|---|---|
| `db` | `pgvector/pgvector:pg18-trixie` | PostgreSQL 18 + pgvector: tabla única de embeddings, FTS, cola de ingesta, auditoría |
| `ollama` | `ollama/ollama` | `gemma3:4b` (planner, destilación, síntesis) y `bge-m3` (embeddings) |
| `docling-serve` | `quay.io/docling-project/docling-serve-cpu` | Extrae PDF/DOCX/PPTX a Markdown para la ingesta de documentos locales — [ADR-0010](adrs/0010-docling-reemplaza-pdfbox.md) |
| `api` | build propio (Java) | Ingesta, retrieval, orquestación, UI estática, reranker y endpoint de Teams |

`compose.yml` es el perfil base en CPU. `compose.gpu.yml` es un override que reserva la GPU para
`gemma3:4b`; `bge-m3` se fija a CPU (`make pin-embeddings-cpu`) porque 4 GB de VRAM no sostienen
los dos modelos a la vez. `make up` detecta la GPU sola (via `nvidia-smi`) y aplica el override
automaticamente -- el perfil CPU solo se usa cuando de verdad no hay tarjeta NVIDIA visible.
`gemma3:4b` ni siquiera entra completo en esa VRAM (60% GPU / 40% CPU medido en vivo); se investigó
una alternativa que sí entra 100% pero se pospuso integrarla -- ver
[`docs/investigacion-vram-y-modelo-llm.md`](investigacion-vram-y-modelo-llm.md) y
[ADR-0009](adrs/0009-bonsai-8b-integracion-pospuesta.md).

`compose.docling-gpu.yml` es otro override opcional, independiente de los de arriba: cambia
`docling-serve` a la imagen CUDA (`docling-serve-cu130`) para correr layout y tablas en GPU en vez
de CPU. Comparte la misma VRAM que Bonsai/Ollama si corren a la vez -- ver el comentario del propio
archivo, incluido un bug conocido de fuga de VRAM en docling-serve sin resolver a la fecha.

## Módulos (Spring Modulith)

Paquete base `co.g3a.baseconocimiento`. Cada módulo con su `internal/` oculto y las fronteras
verificadas por ArchUnit y `ApplicationModules.verify()` en cada build (`ArquitecturaTest`):

| Módulo | Responsabilidad |
|---|---|
| `ingesta` | Conectores (documentos locales, repos Git, Teams, Azure DevOps), chunking, destilación, bursting |
| `recuperacion` | Las 4 señales, RRF, cross-encoder, expansión de contexto |
| `orquestacion` | Planner, executor, las 6 herramientas, síntesis, la fachada `Consultar` |
| `modelos` | Cliente de embeddings y cross-encoder ONNX |
| `llm` | Cliente de Ollama (chat, streaming, salida estructurada) |
| `web` | Adaptador UI HTML/JS: REST, SSE, estáticos |
| `teams` | Adaptador Bot Connector |
| `seguridad` | Filtro de token Bearer sobre el API programático |
| `compartido` | Tipos de dominio: `Cita`, `Fragmento`, `Proyecto`, `Respuesta` |

**La regla que ArchUnit hace cumplir**: `web`, `teams` y `seguridad` solo pueden depender de la
fachada de `orquestacion` y de `compartido`. Nunca de `recuperacion`, `ingesta`, `modelos` ni
`llm`. Es el límite que hace que "tres adaptadores reemplazables" signifique algo, no solo una
intención escrita.

### Núcleo compartido y adaptadores

Una sola fachada:

```java
Respuesta consultar(Pregunta pregunta, ProyectoId proyecto, Filtros filtros)
```

`ProyectoId` acota el corpus **antes** de que el planner elija herramientas — es la segmentación
multi-tenant del MVP (ver Autenticación más abajo). `Consultar` expone además `previsualizar`
(solo señal 1, sin embeddings ni reranker) y `responderEnStreaming` (citas de inmediato +
`Flux<String>` token a token), las dos operaciones que sostienen la UI web de F4.

## Esquema de datos

Cuatro tablas más la cola (`V1__esquema.sql`). La decisión central: **todas las fuentes caen en la
misma tabla `chunks`** — ver [ADR-0001](adrs/0001-tabla-unica-de-embeddings.md).

- `sources` — una fila por fuente (`local_docs`, `local_git`, `teams_channel`, `azure_devops`), con
  `config jsonb`, `project_id` y su propia cadencia de refresco.
- `documents` — `source_id`, `external_id`, `uri`, `title`, `raw_text`, `project_id`, `acl jsonb`,
  `content_hash` (evita re-embeber lo que no cambió).
- `chunks` — `document_id`, `ord`, `kind`, `text`, `distilled jsonb`, `embedding vector(1024)`,
  `fts tsvector` generada, `project_id`, `source_updated_at`. **No** tiene `acl` propia todavía —
  ver [ADR-0007](adrs/0007-acl-por-fuente-pendiente.md).
- `query_log` — cada consulta con su plan, herramientas ejecutadas, candidatos y respuesta.

Índices: HNSW `vector_cosine_ops` sobre `embedding`, GIN sobre `fts` (`spanish`), btree sobre
`(source_id, project_id)` y `source_updated_at`.

**El texto crudo nunca entra al espacio vectorial** — ver
[ADR-0003](adrs/0003-no-embeber-texto-crudo.md). El embedding ancla en
`distilled.searchable_question + distilled.summary`; el crudo alimenta solo FTS.

## Ingesta

Cuatro conectores (`ConectorDocumentosLocales`, `ConectorReposLocales`, `ConectorTeamsGraph`,
`ConectorAzureDevOps`) escriben todos en la misma `chunks`. Los tres últimos están deshabilitados
por defecto; el de documentos locales es el que sostiene la demo.

Cada conector es **incremental por hash de contenido**: para cada archivo compara `content_hash`
contra lo indexado y solo trocea, destila y embebe lo que cambió. Los documentos que ya no existen
en el origen se detectan como huérfanos y se borran (`documentosHuerfanos` → `eliminarDocumento`),
así que quitar un archivo de la carpeta lo saca de las respuestas. Lo que cambió entra a la cola
`ingest_jobs` en Postgres; `TrabajadorEmbebido` la drena de fondo (`@Scheduled` cada 2 s,
`SELECT … FOR UPDATE SKIP LOCKED`) sin bloquear las consultas.

**El relevo ya no se dispara a mano** (F8): `RelevadorDeFuentesProgramador` corre
`RelevadorDeFuentes.relevarTodas()` cada `kb.ingesta.relevo.intervalo-ms` (default 900000 ms = 15
min), bajo un candado en memoria por tipo de conector que también usa el botón "Reindexar ahora" de
la consola de administración (F9), así que no pueden pisarse. `sources.refresh_seconds` (F0) quedó
como metadato informativo, no como disparador — se releva por tipo de conector, no por fila, porque
`local_git` y `azure_devops` descubren/crean varias fuentes por corrida y no tienen forma de
sincronizar "solo esta fila". El porqué del sondeo por hash (en vez de `WatchService`/inotify, que
no cruza el borde Windows → WSL2 → contenedor) y el resto del diseño real están en
[F8 en el plan](plans/plan-base-conocimiento.md#f8--la-carpeta-vigilada-ingesta-sin-comandos). La
explicación del modelo completo, en simple, está en
[Cómo funciona la ingesta](plans/plan-base-conocimiento.md#cómo-funciona-la-ingesta-en-simple).

**Consola de administración** (F9, `admin.html`): estado de fuentes por tipo, cola de ingesta,
reindexado manual y carga de archivos (apagada por defecto, `kb.ingesta.carga-habilitada=false`).
Vive en el módulo `ingesta` (`AdminController`), no en `web` — ver
[la decisión en el plan](plans/plan-base-conocimiento.md#f9--consola-de-administración-en-la-ui--completado).
`/api/admin/ayuda` y `/api/admin/proyectos` quedan fuera de `ApiTokenFilter`, igual que
`/api/chat`/`/api/preview`, porque los usa también la página de chat sin sesión de persona.

`/api/admin/*` ya no vive en un solo controller: `GET /api/admin/feedback` (issue #3) está en
`FeedbackAdminController`, dentro de `orquestacion` — mismo precedente que `OrquestacionController`
para `/api/ask` (un endpoint operativo dentro de su propio módulo, la regla de ArchUnit que aísla a
los adaptadores no le aplica a código intra-módulo), y evita que `ingesta` dependa de algo interno
de `orquestacion` solo para exponerlo. `ApiTokenFilter` sigue cubriendo la ruta igual: empareja por
prefijo de URI, no por el módulo que la expone.

## Pipeline de 7 etapas (`/api/ask`, `/api/chat`)

1. **Planificador** elige herramientas con salida estructurada forzada por Ollama.
2-3. **Executor** las corre en paralelo sobre hilos virtuales, aislando el fallo de una sin tumbar
   las demás.
4. **FusionDeHerramientas** deduplica por chunk entre herramientas, quedándose con el mejor puntaje.
   Justo después, **UmbralRelevancia** (ADR-0008) clasifica el mejor `rerank` en tres estados:
   `INSUFICIENTE` salta directo a un mensaje fijo de "sin información" sin llamar a Ollama;
   `SUFICIENTE` sigue de largo a sintetizar; `AMBIGUO` (la zona donde un solo score no certifica
   relevancia real — hay un contraejemplo documentado en el ADR) pasa por una segunda capa,
   **VerificadorGrounding**: una clasificación binaria corta contra Ollama, no la síntesis completa.
   El umbral y el techo de confianza son dinámicos según el tamaño del corpus del proyecto, no
   números fijos.
5. El **Orquestador** expande cada fragmento elegido con sus vecinos por `ord` (se necesita tanto
   para sintetizar como para que `VerificadorGrounding` tenga contexto que evaluar; se salta solo si
   la etapa 4 ya dio `INSUFICIENTE`).
6. **Sintetizador** redacta en streaming, citando con `[n]` y señalando contradicciones en la
   propia prosa.
7. **QueryLogRepositorio** registra pregunta, plan, herramientas, candidatos, respuesta y citas —
   incluida la traza de un rechazo por umbral o por el verificador de grounding.

## Retrieval híbrido de 4 señales (`/api/search`)

FTS (`ts_rank_cd` sobre GIN) + denso (coseno sobre HNSW) + supresión por IDF + decaimiento
exponencial por antigüedad. Cada señal produce su lista ordenada; `RrfFusion` las combina con
`score(d) = Σ peso / (60 + rango)`, sin normalizar puntajes — ver
[ADR-0002](adrs/0002-rrf-sobre-normalizacion.md). Una quinta señal (dispersa de BGE-M3) se evaluó y
se descartó — ver [ADR-0004](adrs/0004-senal-dispersa-descartada.md). El cross-encoder
(`bge-reranker-v2-m3` sobre ONNX Runtime) reordena el top ~20 y corta en el top 10.

## Autenticación

`kb.api-token` (`KB_API_TOKEN`) vacío por defecto: sin autenticación. Con un valor, el filtro
`ApiTokenFilter` (módulo `seguridad`) exige `Authorization: Bearer <token>` en `/api/ask`,
`/api/search`, `/api/ingest/*` y la mayor parte de `/api/admin/*` (F9) — los endpoints
programáticos y de operación.

Quedan **fuera** de este filtro, a propósito:

- `/api/messages` — el Bot Connector ya valida su propio JWT contra Azure AD
  (`ValidadorTokenBotFramework`); exigir además este token rechazaría tráfico legítimo de Azure Bot
  Service.
- `/api/chat` y `/api/preview` — la UI web de F4. El `EventSource` nativo del navegador no puede
  mandar cabeceras propias, y estas dos rutas son el "keyword search on landing" pensado para no
  tener fricción. El MVP no tiene login de persona (Entra ID queda fuera, según los Supuestos del
  plan): este token protege llamadas programáticas, no la página que cualquiera con acceso a la
  red ya puede abrir.
- `/api/admin/ayuda` y `/api/admin/proyectos` (F9/F10) — el botón `?` y el selector de proyecto
  viven también en la página de chat, con el mismo problema. Ambos son de solo lectura y no
  exponen contenido del corpus; el resto de `/api/admin/*` (fuentes, reindexar, subir archivos)
  sí exige el token — la consola de administración (`admin.html`) lo pide una vez y lo guarda en
  `sessionStorage`, porque a diferencia del chat usa `fetch`, no `EventSource`.
- `/api/feedback` (issue #3) — los botones 👍/👎 de la página de chat, mismo motivo que
  `/api/chat`: sin sesión de persona, no hay token que mandar. Riesgo aceptado y documentado en
  `Consultar.registrarFeedback`: nada valida que quien manda el feedback vio realmente esa
  respuesta. `GET /api/admin/feedback` sí exige el token, igual que el resto de `/api/admin/*`.

La segmentación multi-tenant real es `ProyectoId`, no el token: cada consulta acota el corpus por
`project_id` antes de que el planner corra. La ACL por fuente (`documents.acl`) existe como columna
pero no se hace cumplir todavía — decisión documentada como pendiente en
[ADR-0007](adrs/0007-acl-por-fuente-pendiente.md).

## Dónde termina Spring AI

Spring AI 2.0 se usa **solo como cliente de Ollama**: `OllamaChatModel` para planner, destilación y
síntesis en streaming con salida estructurada, y `OllamaEmbeddingModel` para los vectores. No se
usan sus abstracciones de RAG ni `PgVectorStore` — un `VectorStore` no sabe expresar cuatro señales
independientes fusionadas por RRF. El retrieval va escrito a mano en SQL sobre `JdbcClient`.

## Equivalencias con el artículo de Cerebras

| Cerebras | Aquí |
|---|---|
| Postgres + una tabla de embeddings (3072-dim) | PostgreSQL 18 + pgvector, `embedding vector(1024)` |
| Destilación LLM de hilos de Slack | Destilación de hilos de Teams con `gemma3:4b`, salida JSON forzada |
| Bursting con gates IDF ≥ 4.0 y ≥ 200 caracteres | Mismos umbrales |
| CocoIndex para código | Chunker heurístico clase → método → bloque, incremental por commit |
| 4 señales: FTS + vector + IDF + age decay | Idénticas, todas resueltas en SQL sobre Postgres |
| RRF k=60 | `RrfFusion`, sin normalizar puntajes |
| Cross-encoder 0–10, top 10 | `bge-reranker-v2-m3` sobre `onnxruntime-java` |
| `search_code` con ripgrep | `ripgrep` invocado desde el contenedor |
| 6 herramientas tras un planner | Planner con esquema JSON forzado en Ollama |
| MCP para agentes | Fuera del MVP; el núcleo queda listo para exponerlo después |
| Sin corte de relevancia: siempre top-10 al LLM | **Desviación deliberada** — umbral dinámico + verificador de grounding de dos capas antes de sintetizar, ver [ADR-0008](adrs/0008-umbral-de-relevancia-antes-de-sintesis.md) |
