# Base de Conocimiento interna — arquitectura Cerebras, en Spring Boot, 100 % local y sin costo

## Contexto

El artículo *How we built our knowledge base* de Cerebras describe un RAG interno que responde
más de 15 000 preguntas diarias sobre Slack, código y wikis. Su valor no está en "hacer RAG", sino
en decisiones concretas: **destilar los hilos con un LLM antes de indexarlos**, una **única tabla
de embeddings en Postgres** para todas las fuentes, **búsqueda híbrida de cuatro señales**,
**fusión RRF por rango** y un **cross-encoder** antes de sintetizar.

Reproducimos esa arquitectura con tres diferencias:

1. En vez de Slack, **dos adaptadores**: una **UI HTML + JavaScript** y un **bot de Teams**.
2. **Costo cero**: nada de APIs de pago ni recursos en la nube. Modelos abiertos en contenedores.
3. **Núcleo en Spring Boot**, alineado con el estándar de la casa.

El repositorio está prácticamente vacío (solo `LICENSE`): esto es *greenfield*.

### Hardware medido (no supuesto)

| | Medido |
|---|---|
| CPU | i7-11800H — 8 núcleos / 16 hilos, con **AVX-512 y VNNI** |
| RAM | 31,7 GB totales, 15,6 GB libres al momento de medir |
| GPU | NVIDIA T600 Laptop, **4 GB VRAM**, CC 7.5, driver 596.52 (CUDA en WSL2 disponible) |
| Disco | D: 251 GB libres — **C: solo 45 GB libres** |
| Docker | CLI 28.1.1 instalado, **engine detenido**; WSL2 con distro `docker-desktop` |

### Decisiones y su razón

| Decisión | Elección | Razón |
|---|---|---|
| Lenguaje | **Java 21 LTS + Spring Boot 4.1 + Modulith 2.1** | Boot 3.5 llegó a fin de vida OSS el 30-jun-2026: arrancar ahí sería nacer sin parches. Java 21 (no 25) porque es el JDK instalado en la máquina, y mantener vivo el `mvn test` local fue justo el argumento que le ganó a Rust. Subir a 25 es una línea. **Actualización**: ya se subió — el proyecto compila a Java 25 desde la sincronización con `base-conocimiento-sandbox` |
| Acceso a modelos | **Spring AI 2.0**, solo para Ollama | Su línea 1.x quedó atada a Boot 3.5. Se usa para chat, streaming y salida estructurada — **no** su `VectorStore` |
| Persistencia | PostgreSQL 18 + pgvector 0.8.6, Flyway, `JdbcClient` | Última estable; su E/S asíncrona acelera escaneos HNSW y GIN |
| Generación | Ollama con **un solo** `gemma3:4b` | 4 GB de VRAM no sostienen dos modelos residentes; un modelo evita recargas por consulta. Originalmente `qwen3:4b`; reemplazado en F4 — ver hallazgos |
| Embeddings | **`bge-m3` servido por Ollama** (1024-dim) | Elimina DJL, la exportación a ONNX y el jar de 100 MB |
| Reranking | `bge-reranker-v2-m3` sobre `onnxruntime-java` | Ollama no sirve cross-encoders; es la pieza que el artículo llama decisiva |
| Señales | **4, como el artículo** | La quinta (dispersa de BGE-M3) choca con el límite de 1 000 no-nulos de pgvector |
| Teams | Protocolo **Bot Connector** implementado directo | Todos los SDK de Bot Framework están retirados; el servicio no |
| Fuentes | Documentos locales, repos Git locales, Teams (Graph), Azure DevOps | |

**Sobre Teams**: el SDK de Java murió en noviembre de 2023 y el resto del Bot Framework SDK tuvo
soporte final hasta diciembre de 2025, con el repositorio archivado en enero de 2026. El sucesor,
Microsoft 365 Agents SDK, no soporta Java. Pero Azure AI Bot Service sigue ejecutando bots V4 sin
fin de vida anunciado: **implementar el protocolo es hoy la opción más duradera, no un mal menor**.

### Supuestos (avísame si alguno no aplica)

- **Autenticación**: token compartido opcional (`KB_API_TOKEN`) más segmentación por `project_id`,
  y ACL a nivel de fuente. Entra ID queda fuera del MVP.
- **Teams y Azure DevOps** no cobran pero exigen credenciales: registro de app con permisos Graph
  aprobados por un admin, y un PAT. Los conectores quedan implementados y **deshabilitados por
  defecto**; el producto arranca y se demuestra completo sin ellos.
- **Calidad**: modelos abiertos en CPU no igualan a Cerebras en fluidez ni latencia. La
  arquitectura de retrieval sí se reproduce fielmente, que es donde está el aporte del artículo.

---

## Arquitectura

### Contenedores (3)

| Servicio | Imagen | Rol |
|---|---|---|
| `db` | `pgvector/pgvector:pg18-trixie` | PostgreSQL 18.4 + pgvector 0.8.6: tabla única de embeddings, FTS, cola, auditoría |
| `ollama` | `ollama/ollama` | `gemma3:4b` (planner, destilación, síntesis) y `bge-m3` (embeddings) |
| `api` | build propio (Java) | Ingesta, retrieval, orquestación, UI estática, reranker y endpoint de Teams |

Imagen base `eclipse-temurin:25-jre-noble` (era `21` hasta la sincronización con
`base-conocimiento-sandbox`) — **glibc, no Alpine**: las librerías nativas de ONNX
Runtime no corren sobre musl. Incluye el binario `ripgrep` (~5 MB) para `search_code`. Jar por
capas de Spring Boot para que las reconstrucciones solo repongan la capa de aplicación.

El worker de ingesta corre en el mismo proceso, sobre una cola en Postgres con
`SELECT … FOR UPDATE SKIP LOCKED`: sin Redis, sin contenedor extra. Los **hilos virtuales**
(`spring.threads.virtual.enabled=true`) cubren el fan-out paralelo de las 6 herramientas.

### Dónde termina Spring AI

Spring AI 2.0 se usa **solo como cliente de Ollama**: `OllamaChatModel` para planner, destilación
y síntesis en streaming con salida estructurada, y `OllamaEmbeddingModel` para los vectores.

No se usan sus abstracciones de RAG ni `PgVectorStore`, y esa frontera es deliberada: **un
`VectorStore` no sabe expresar cuatro señales independientes fusionadas por RRF**. El retrieval va
escrito a mano en SQL sobre `JdbcClient`. Adoptar la abstracción ahí sería perder exactamente lo
que hace valioso al artículo, a cambio de comodidad.

Presupuesto de memoria: `bge-m3` ≈ 1,2 GB + `gemma3:4b` q4 ≈ 2,5 GB + reranker ONNX ≈ 1,2 GB +
heap JVM ≈ 1 GB + Postgres ≈ 1 GB ≈ **7 GB**. Holgado en 31,7 GB.

### Perfiles de cómputo

`compose.yml` corre todo en CPU y es el perfil base y reproducible del taller.
`compose.gpu.yml` es un override que reserva la T600 para Ollama. Como 4 GB no alcanzan para
`gemma3:4b` y `bge-m3` a la vez, `bge-m3` se fija a CPU con un Modelfile de `num_gpu 0`: la GPU
queda íntegra para la síntesis, que es lo que el usuario espera.

### Equivalencias con el artículo

| Cerebras | Aquí |
|---|---|
| Postgres + una tabla de embeddings (3072-dim) | PostgreSQL 18 + pgvector, `embedding vector(1024)` |
| Destilación LLM de hilos de Slack | Destilación de hilos de Teams con `gemma3:4b` y salida JSON forzada |
| Bursting con gates IDF ≥ 4.0 y ≥ 200 caracteres | Mismos umbrales |
| CocoIndex para código | Chunker heurístico clase → método → bloque, incremental por commit |
| 4 señales: FTS + vector + IDF + age decay | Idénticas, todas resueltas en SQL sobre Postgres |
| RRF k=60 | `RrfFusion`, sin normalizar puntajes |
| Cross-encoder 0–10, top 10 | `bge-reranker-v2-m3` sobre `onnxruntime-java` |
| `search_code` con ripgrep | `ripgrep` invocado desde el contenedor |
| 6 herramientas tras un planner | Planner con esquema JSON forzado en Ollama |
| MCP para agentes | Fuera del MVP; el núcleo queda listo para exponerlo después |

### Esquema de datos

Cuatro tablas más la cola. La clave es que **todas las fuentes caen en la misma tabla `chunks`**:

- `sources` — una fila por fuente (`local_docs`, `local_git`, `teams_channel`, `azure_devops`),
  con `config jsonb` y su propia cadencia de refresco. Frescura por fuente, sin cron global.
- `documents` — `source_id`, `external_id`, `uri`, `title`, `raw_text`, `metadata jsonb`,
  `project_id`, `acl jsonb`, `content_hash`, `source_updated_at`.
- `chunks` — `document_id`, `ord`, `kind`, `text`, `distilled jsonb`, `metadata jsonb`,
  `embedding vector(1024)`, `fts tsvector` generada, `source_updated_at`.
- `query_log` — cada consulta con su plan, herramientas ejecutadas, candidatos y respuesta. Sin
  esto no se depuran respuestas malas a escala; el artículo lo señala explícitamente.

Índices: HNSW `vector_cosine_ops` sobre `embedding`, GIN sobre `fts` (configuración `spanish`),
btree sobre `(source_id, project_id)` y `source_updated_at`. Migraciones con Flyway.

**El texto crudo nunca entra al espacio vectorial.** El embedding ancla en
`distilled.searchable_question + distilled.summary`; el crudo alimenta solo FTS. Campos
destilados: `searchable_question`, `summary`, `resolution`, `systems_mentioned`, `code_references`.

### Cómo funciona la ingesta, en simple

El modelo mental que se le promete al usuario es de una sola frase: **pon los archivos en una
carpeta y olvídate**. Todo lo demás pasa solo.

1. Copias un archivo a `vault/documentos` (documentos: `.md`, `.txt`, `.pdf`) o clonas un repo
   dentro de `vault/repos` (código) — el vault vive fuera del repositorio, ver
   [ADR-0011](../adrs/0011-vault-unificado.md). No hay que registrar nada ni correr ningún comando.
2. Cada cierto tiempo el sistema vuelve a leer esa carpeta y compara contra lo que ya tiene
   indexado, archivo por archivo, usando un hash del contenido:

   | Lo que encuentra | Lo que hace |
   |---|---|
   | Archivo nuevo | Lo trocea e indexa |
   | Archivo con hash distinto | Reemplaza sus fragmentos por los nuevos |
   | Archivo con el mismo hash | **Nada** — ni destila ni embebe, no gasta LLM |
   | Archivo que ya no está | Borra sus fragmentos: deja de aparecer en las respuestas |

3. Los fragmentos nuevos o cambiados entran a una cola en Postgres. Un worker los va tomando de a
   poco, los destila con el LLM y calcula su embedding. Nada de esto bloquea a quien esté
   preguntando mientras tanto.
4. En cuanto un fragmento termina ese paso, ya puede aparecer citado en una respuesta.

Que el paso 2 se apoye en un **hash del contenido y no en la fecha de modificación** es lo que hace
barata la re-lectura: releer 500 archivos para descubrir que 499 no cambiaron cuesta leer 500
archivos de disco, no 500 llamadas al LLM. Ese detalle es el que permite que el relevo corra
seguido sin costo.

**Estado**: los pasos 1, 2, 3 y 4 están implementados desde F1 (el conector de documentos locales
ya compara hashes, salta lo que no cambió y borra los huérfanos). Lo único que falta es que el
paso 2 ocurra **solo, cada X tiempo**: hoy hay que dispararlo a mano con `make ingest`. Eso es
[F8](#f8--la-carpeta-vigilada-ingesta-sin-comandos).

### Módulos (Spring Modulith)

Paquete base `co.g3a.baseconocimiento`, siguiendo la convención de `co.g3a`. Cada módulo con su
`internal/` oculto y las fronteras verificadas por Modulith y ArchUnit en el pipeline:

| Módulo | Responsabilidad |
|---|---|
| `ingesta` | Conectores, chunking, destilación, bursting, embeddings de ingesta |
| `recuperacion` | Las 4 señales, RRF, cross-encoder, expansión de contexto |
| `orquestacion` | Planner, executor, las 6 herramientas, síntesis, la fachada `Consultar` |
| `modelos` | Cliente de embeddings y cross-encoder ONNX |
| `llm` | Cliente de Ollama (chat, streaming, salida estructurada) |
| `web` | Adaptador UI HTML/JS: REST, SSE, estáticos |
| `teams` | Adaptador Bot Connector |
| `compartido` | Tipos de dominio: `Cita`, `Fragmento`, `Proyecto`, `Respuesta` |

**La regla que ArchUnit debe hacer cumplir**: `web` y `teams` solo pueden depender de la fachada
de `orquestacion` y de `compartido`. Nunca de `recuperacion`, `ingesta`, `modelos` ni `llm`. Ese
es el límite que hace que "dos adaptadores" signifique algo.

### Núcleo compartido y adaptadores

Una sola fachada:

```java
Respuesta consultar(Pregunta pregunta, ProyectoId proyecto, Filtros filtros)
```

- **UI HTML/JS** (`web`): una página sin build, JavaScript vanilla, estáticos servidos por Spring.
  Muestra resultados de FTS crudo al instante mientras el pipeline completo corre detrás (el
  "keyword search on landing" del artículo) y transmite la síntesis por SSE.
- **Teams** (`teams`): Bot Connector directo. Responde en el hilo con Adaptive Cards que llevan
  las citas como enlaces, y envía indicador de escritura mientras el pipeline corre, porque en
  CPU la síntesis tarda.

---

## Plan de ejecución

### F0 — Andamiaje ✅ completado

- `pom.xml` con versiones fijadas: Java 21 (hoy 25), Spring Boot 4.1, Modulith 2.1, Spring AI 2.0,
  Flyway, ArchUnit, jqwik, Testcontainers. Jackson 3 y Jakarta EE 11 vienen con Boot 4.
- `compose.yml` con los 3 servicios, healthchecks y **bind mounts a D:** para modelos de Ollama
  y datos de Postgres. `compose.gpu.yml` como override. `.env.example`, `Makefile`.
- Plantilla `wslconfig.example` versionada, con instrucciones de copia a `%USERPROFILE%`.
- `Dockerfile` multi-etapa con jar por capas, base `temurin:21-jre-noble` (hoy `25`) y `ripgrep`.
- Migración Flyway `V1__esquema.sql`; prueba ArchUnit que ya declare la regla de adaptadores.
- **Criterio de salida**: `docker compose up` deja los 3 servicios sanos y `/actuator/health`
  reporta db y ollama.

### F1 — Almacenamiento, modelos e ingesta base ✅ completado

- `modelos`: cliente de embeddings contra Ollama (`bge-m3`) y `RerankerOnnx` sobre
  `onnxruntime-java` con tokenizador de `ai.djl.huggingface:tokenizers`, hash del artefacto fijado.
- `ingesta`: cola en Postgres, `content_hash` para re-embeber solo lo cambiado, conector de
  documentos locales con chunking por headings y PDF con **Apache PDFBox** (Java puro, sin
  dependencias del sistema operativo).
- **Criterio de salida**: apuntas a `vault/documentos`, corres `make ingest` y `chunks` queda
  poblada con `embedding` y `fts` no nulos.

**Actualización posterior — Docling reemplaza PDFBox ([ADR-0010](../adrs/0010-docling-reemplaza-pdfbox.md)):**
PDF (y ahora también DOCX y PPTX) ya no se extrae con PDFBox por ventanas de párrafos, sino con
Docling vía `docling-serve` (cuarto contenedor). El Markdown que devuelve conserva encabezados
reales, así que estos formatos pasan por el mismo `ChunkerEncabezados` que `.md`/`.txt` — dejó de
hacer falta un chunker aparte para ellos.

### F2 — Retrieval híbrido de 4 señales ✅ completado

- FTS con `ts_rank_cd` sobre GIN, configuración `spanish`; denso por coseno sobre HNSW;
  supresión por IDF; decaimiento exponencial por antigüedad. Cada señal produce su lista ordenada.
- `RrfFusion`: `score(d) = Σ peso / (60 + rango)`, **sin normalizar puntajes**; la ausencia aporta
  0 y no penaliza. Luego dedup y tope por documento hasta ~20 candidatos diversos.
- Cross-encoder, puntaje 0–10, corte en top 10.
- **Criterio de salida**: `POST /api/search` devuelve resultados con la traza de las 4 señales y
  el rango antes y después del reranker.

### F3 — Pipeline de 7 etapas ✅ completado

- Planner con `format` de esquema JSON en Ollama: elige herramientas según pregunta y proyecto.
- Las 6 herramientas: `search_docs`, `search_code` (ripgrep), `search_unified`, `who_knows`,
  `recent_commits`, `subsystem_index`.
- Executor con fan-out sobre hilos virtuales; expansión de contexto con secciones vecinas y
  hermanos del hilo; síntesis en streaming, obligada a adjuntar citas y a señalar evidencia
  contradictoria en vez de dar falsa certeza.
- Todo queda registrado en `query_log`.
- **Criterio de salida**: `POST /api/ask` devuelve respuesta citada y la traza de las 7 etapas.

Las 7 etapas tal como quedaron implementadas: (1) `Planificador` elige herramientas con salida
estructurada forzada por Ollama; (2-3) `Executor` las corre en paralelo sobre hilos virtuales,
aislando el fallo de una sin tumbar las demás; (4) `FusionDeHerramientas` deduplica por chunk
entre herramientas y se queda con el mejor puntaje; (5) el propio `Orquestador` expande cada
fragmento elegido con sus vecinos por `ord`; (6) `Sintetizador` redacta en streaming, citando
con `[n]` y señalando contradicciones en la propia prosa; (7) `QueryLogRepositorio` registra
pregunta, plan, herramientas, candidatos, respuesta y citas. `Consultador` implementa la fachada
`Consultar` quedándose solo con la `Respuesta` final; `OrquestacionController` en cambio expone
la `EjecucionPipeline` completa por `/api/ask`, igual que `RecuperacionController` hace con la
traza de F2 — los adaptadores de F4/F5 hablarán con `Consultar`, nunca con esta forma rica.

### F4 — Adaptador UI HTML/JS ✅ completado

- REST más SSE, estáticos en `resources/static`, búsqueda por palabra clave inmediata, citas
  clicables, selector de proyecto.
- **Criterio de salida**: preguntas y respuestas completas en `localhost:8080`.

`Consultar` ganó dos operaciones nuevas para poder construir esto sin romper su límite: `previsualizar`
(solo señal 1, sin embeddings ni reranker — el "keyword search on landing" del artículo) y
`responderEnStreaming` (citas de inmediato + `Flux<String>` token a token). `Orquestador` comparte las
etapas 1-5 entre el modo bloqueante de F3 y este modo en streaming a través de un `PreSintesis` interno.
`recuperacion` ganó `Buscador.buscarPalabraClave` para sostener la vista previa. El adaptador en sí
(`web.ChatController`) solo conoce `Consultar` y `compartido`; `index.html`/`app.js` son vanilla, sin
build, con `EventSource` nativo contra `GET /api/chat` (no POST: `EventSource` del navegador solo sabe
hacer GET) y `fetch` contra `POST /api/preview`.

Verificado en vivo contra el stack real (`docker compose`, con `qwen3:4b`, `bge-m3` y el reranker ya
descargados): `/api/preview` responde con resultados de FTS en milisegundos; `/api/chat` entrega primero
el evento `citas`, luego el texto token a token, y cierra con `fin` — el flujo de tres eventos que
`app.js` espera funciona de punta a punta.

### F5 — Adaptador Teams (protocolo Bot Connector) ✅ implementado — verificación en vivo pendiente

- Validación del JWT entrante: documento OpenID de `login.botframework.com`, caché del JWKS
  (vía `NimbusJwtDecoder.withJwkSetUri`), verificación de emisor, audiencia (`MicrosoftAppId`)
  y coincidencia del claim `serviceUrl`. Modo emulador conmutable por `KB_TEAMS_EMULATOR`, que
  usa otro emisor y metadatos.
- Modelo `Activity`, token por `client_credentials` contra
  `login.microsoftonline.com/botframework.com`, respuesta con
  `POST {serviceUrl}/v3/conversations/{id}/activities/{id}`, más indicador de escritura.
- Adaptive Cards con citas y manifiesto de Teams (`docs/teams/manifest.json`), con guía de
  registro en Azure Bot F0 documentada pero no ejecutada (`docs/teams/registro-azure-bot.md`).
- **Criterio de salida**: conversación completa contra el Bot Framework Emulator apuntando a
  `http://localhost:8080/api/messages`, con citas renderizadas en la tarjeta. **Sin ejecutar
  todavía en esta sesión**: Docker sí estaba disponible y la suite completa corrió contra él
  (`./mvnw test`: 55 pruebas, 0 fallos, incluyendo Postgres real vía Testcontainers y
  `ArquitecturaTest` con `teams` ya poblado), pero no se levantó el `compose.yml` completo
  (Ollama con los modelos descargados) ni se instaló el Bot Framework Emulator para probar una
  conversación de punta a punta como sí se hizo en vivo en F4. Queda pendiente el paso 7 de la
  sección Verificación contra el stack real antes de dar F5 por cerrado con la misma confianza
  que F0-F4.

### F6 — Conectores restantes ✅ implementado — verificación en vivo pendiente

- Repos Git locales con JGit: chunking heurístico clase → método → bloque con división de grano
  grueso a fino cuando el fragmento excede el tamaño, e incremental por commit guardando el
  último SHA por repo (`sources.sync_state`).
- Teams por Graph: canal por fuente, delta query de mensajes raíz + respuestas, destilación del
  hilo completo por LLM y bursting de mensajes individuales (gate IDF ≥ 4.0 y ≥ 200 caracteres).
- Azure DevOps: work items por WIQL + `workitemsbatch`, y páginas de wiki (árbol completo +
  contenido por página) con chunking por headings, reutilizando `ChunkerEncabezados` de F1.
- Los tres deshabilitados por defecto en `.env.example` y en `compose.yml`.
- **Criterio de salida**: cada conector ingiere sobre un fixture y produce chunks consultables.
  Cubierto con WireMock doblando Graph y la REST API de Azure DevOps (el gate de bursting se
  verifica contra `term_stats` real, no doblado) y con un repositorio JGit real más Postgres real
  vía Testcontainers (repos locales). **Sin ejecutar todavía**: ingesta contra un tenant de
  Microsoft 365 u organización de Azure DevOps reales — exigen credenciales que este entorno no
  tiene (ver Supuestos del plan), igual que el paso 7 de F5 con el Bot Framework Emulator.

### F7 — Producto instalable 🟡 en curso

- Proyectos y filtrado del corpus antes del planner: ✅ ya estaban resueltos desde F0-F2
  (`ProyectoId`, `project_id` en `sources`/`documents`/`chunks`).
- Token de API: ✅ completado. `ApiTokenFilter` (módulo nuevo `seguridad`) exige
  `Authorization: Bearer <KB_API_TOKEN>` en `/api/ask`, `/api/search` y `/api/ingest/*`. Deja afuera
  `/api/messages` (JWT propio del Bot Connector) y `/api/chat`/`/api/preview` (la UI web no tiene
  login de persona en este MVP; `EventSource` tampoco podría mandar la cabecera). Detalle completo
  en `docs/architecture.md#autenticación`.
- ACL por fuente: ⏸️ **pendiente a propósito, no implementada**. `documents.acl` existe desde F0
  pero nunca se propagó a `chunks` ni se hizo cumplir en ningún query — decisión documentada en
  [ADR-0007](../adrs/0007-acl-por-fuente-pendiente.md), incluyendo Row-Level Security (nativo de
  Postgres, sin instalar nada) como el camino recomendado si se retoma.
- `README.md`, `docs/architecture.md` y `docs/adrs/`: ✅ completados. Seis ADRs con las decisiones
  duras (tabla única, RRF, no embeber crudo, señal dispersa descartada, protocolo Bot Connector,
  chunker heurístico) más el ADR-0007 de la ACL pendiente.
- `make seed`: ✅ agregado como alias de `make ingest` (el nombre que esta misma sección de
  Verificación ya usaba).
- **Pendiente todavía**: ACL real (ver arriba); ejercitar el criterio de salida completo
  (clonar en limpio, sin ningún estado previo) no se repitió en esta sesión porque el stack ya
  estaba levantado de una sesión anterior — ver Hallazgos de F7 para lo que sí se verificó.
- **Criterio de salida**: clonar, `cp .env.example .env`, `docker compose up`, preguntar y obtener
  respuesta citada, sin ninguna cuenta externa.

### F8 — La carpeta vigilada: ingesta sin comandos ✅ completado

El objetivo era cerrar la promesa de "pon los archivos en una carpeta y olvídate" (ver
[Cómo funciona la ingesta, en simple](#cómo-funciona-la-ingesta-en-simple)). Todo el trabajo
difícil ya estaba hecho desde F1: comparar hashes, saltar lo que no cambió, borrar huérfanos y
encolar lo nuevo. Solo faltaba el disparador periódico.

- `RelevadorDeFuentesProgramador`: un `@Scheduled` (`kb.ingesta.relevo.intervalo-ms`, default
  900000 ms = 15 min) que corre `RelevadorDeFuentes.relevarTodas()`, la cual invoca los cuatro
  conectores (`local_docs`, `local_git`, `teams_channel`, `azure_devops`) bajo un candado por tipo.
  **Diseño real, distinto del boceto original**: no se releva por fila de `sources` con
  `refresh_seconds` individual — ver el primer hallazgo de F8 más abajo sobre por qué esa idea no
  encaja con la forma real de los conectores. `refresh_seconds` queda como metadato informativo en
  la fila, no como el disparador.
- **Sondeo por hash, no eventos del sistema de archivos.** La opción aparentemente más elegante
  (`WatchService`/inotify, reaccionar al instante en que el archivo se guarda) **no funciona en
  este entorno**: los eventos de inotify no cruzan el borde Windows → WSL2 → contenedor sobre un
  bind mount. Un watcher quedaría escuchando en silencio, sin fallar y sin disparar nunca. El
  sondeo con hash de contenido no es aquí una simplificación: es la única forma correcta.
- Sin solapes: un candado en memoria (`ReentrantLock` por tipo de conector, dentro de
  `RelevadorDeFuentes`) impide que el relevo automático y el botón "Reindexar ahora" de F9 corran
  el mismo conector a la vez. En memoria, no en la base, porque este proyecto corre un único
  contenedor `api` — no hay una segunda instancia con la que coordinarse.
- El resultado de cada relevo (el `Resumen` que cada conector ya devuelve) se guarda **en memoria**,
  no en `sources.sync_state` — esa columna es de cada conector para su propio token incremental
  (`last_sha`, `delta_link`) y sobreescribirla habría perdido esa información. La consola de F9 lee
  el último resultado por tipo vía `RelevadorDeFuentes.ultimoResultado(tipo)`.
- Apagable con `kb.ingesta.relevo.habilitado=false`, mismo patrón que
  `kb.ingesta.worker.habilitado` de F1.
- **Criterio de salida, verificado en vivo contra el stack real**: con `KB_INGESTA_RELEVO_INTERVALO_MS=15000`,
  se copió un `.md` nuevo a `vault/documentos`, se esperó el intervalo **sin correr ningún comando**, y
  `documents`/`chunks` lo reflejaron solos (log: *"Ingesta de documentos locales: 3 vistos, 2
  actualizados, 1 sin cambios, 0 eliminados, 4 chunks nuevos"*). Se borró el archivo, se esperó
  otro intervalo, y desapareció de la base (*"1 eliminados"*). `.env` se restauró al default
  (900000 ms) después de la prueba.

### F9 — Consola de administración en la UI ✅ completado

Antes de esta fase, operar el sistema exigía terminal: `make ingest`, `make health`, `psql`. Ahora
se puede usar **completo desde el navegador** (`admin.html`).

- Página de administración con: las fuentes agrupadas por tipo de conector y su estado (habilitada,
  último relevo por fila, documentos y fragmentos indexados), la cola de ingesta por estado
  (pending/running/done/failed), y un botón **Reindexar ahora** por tipo que no espera al siguiente
  ciclo de F8 — comparte el mismo candado de `RelevadorDeFuentes`, así que no puede pisarse con el
  relevo automático.
- **Botón de ayuda (`?`) que explica dónde poner los archivos.** Presente tanto en la consola como
  en la página de chat — es ahí donde alguien se pregunta "¿por qué no encuentra mi documento?".
  Al abrirlo explica, en lenguaje de usuario:
  - **En qué carpeta van**: documentos en `vault/documentos`, repositorios de código en
    `vault/repos` — un único `vault` fuera del repositorio clonado (ver
    [ADR-0011](../adrs/0011-vault-unificado.md)).
  - **Por qué hay dos rutas y no una**, que es la confusión garantizada de este proyecto:
    `KB_VAULT_DIR` es la carpeta **en tu máquina** (lo que `compose.yml` monta) y `KB_VAULT_RUTA`
    es la ruta **dentro del contenedor** (`/vault`, fija). Son los dos lados del mismo bind mount
    y sus nombres se parecen demasiado. La ayuda muestra el valor real que el servidor está
    leyendo, no una ruta escrita a mano en el HTML: si alguien cambió `KB_VAULT_DIR`, la ayuda
    tiene que decir la verdad y no la convención.
  - **Qué formatos acepta** (`.md`, `.txt`, `.pdf`) y qué pasa con lo demás.
  - **Cuándo estará disponible**: cada cuánto se releva esa fuente (el `refresh_seconds` real, no
    un número inventado) y que no hace falta correr ningún comando.
  - **Qué pasa si borras un archivo**: deja de aparecer en las respuestas en el siguiente relevo.
- **Carga de archivos desde el navegador**: subes un `.md`/`.txt`/`.pdf` (`<input type="file">`,
  sin drag-and-drop) y queda escrito en `vault/documentos`, del lado del servidor
  (`POST /api/admin/vault/documentos`, multipart). **Apagada por defecto**
  (`kb.ingesta.carga-habilitada=false`): se decidió la segunda de las dos salidas que este plan
  dejó pendientes, la misma que ya usan los conectores opcionales — encenderla es un acto explícito,
  no un default. **Habilitarla exige además** quitar el `:ro` del mount de `KB_VAULT_DIR` en
  `compose.yml`: el bind mount sigue siendo de solo lectura por defecto, y sin ese cambio manual la
  llamada falla con un error de E/S explícito, no en silencio.
- **Decisión de dónde vive**: los endpoints de administración van en el módulo `ingesta`, **no** en
  `web`. `IngestaController` ya sentó el precedente y lo dice en su javadoc: es un endpoint de
  operación que el módulo expone sobre sí mismo, no una puerta de usuario final, y por eso la regla
  de ArchUnit que aísla a los adaptadores no le aplica. La alternativa — servirlos desde `web` —
  obligaría a crear una fachada de administración en `ingesta` y a relajar la regla que es *el*
  contrato del proyecto, a cambio de nada: una pantalla de operación no es un adaptador de usuario
  final. `web` sigue siendo solo el chat (llama a estos endpoints por HTTP desde `app.js`, lo cual
  no cruza ningún límite de paquete Java — el límite de ArchUnit es entre clases Java, no entre
  llamadas de red del navegador).
- El HTML/JS de la consola (`admin.html`/`admin.js`) sigue el mismo criterio que la UI de F4:
  vanilla, sin build. Un campo de token (`sessionStorage`, no persistido) permite operar la consola
  cuando `KB_API_TOKEN` está configurado.
- Interacción con el token de API de F7: `/api/admin/fuentes`, `/reindexar` y `/vault/documentos` sí
  quedan detrás de `KB_API_TOKEN`. `/api/admin/ayuda` y `/api/admin/proyectos` (F10) quedan
  **excluidos** a propósito — ambos son de solo lectura, sin datos del corpus, y los usa también la
  página de chat, que no tiene ninguna sesión de persona logueada que les pase un token (mismo
  problema que `/api/chat` en F7).
- **Criterio de salida, verificado en vivo contra el stack real y en un navegador real** (Chromium
  headless vía Playwright, sin `claude-in-chrome` disponible esta sesión — ver hallazgos de F10):
  se probó con `kb.ingesta.carga-habilitada=true` y el `:ro` quitado a mano en `compose.yml`
  (revertido después de la prueba): `POST /api/admin/vault/documentos` con un `.md` real escribió el
  archivo en `vault/documentos` del host, y un `.exe` rechazado con 400 sin tocar disco. Con la carga en su
  default (`false`), la misma llamada responde 403. En el navegador: la consola carga las fuentes
  agrupadas por tipo con sus conteos reales, la cola de ingesta, el botón "Reindexar ahora" corre el
  relevo y refresca el "último resultado" en pantalla al instante, y el modal de ayuda (componente
  compartido con el chat) muestra las rutas y el intervalo reales.

**Actualización posterior — vault unificado y panel por archivo ([ADR-0011](../adrs/0011-vault-unificado.md)):**
`./corpus` y `./repos` (dos carpetas separadas, dentro del árbol del repo) se fusionaron en un solo
`vault` con dos subcarpetas fijas (`vault/documentos`, `vault/repos`), ubicado **fuera** del
repositorio por defecto (`KB_VAULT_DIR=../vault`). La consola de administración ganó una tabla
estilo *Job Runner* (`GET /api/admin/vault/archivos`) con el estado de **cada archivo** —
detectado/extrayendo/procesando/embebiendo/listo/error, con reintento — en vez de solo el resumen
agregado por fuente que ya daba la sección "Fuentes".

### F10 — La UI de chat, usable de verdad ✅ completado (progreso, con alcance recortado — ver hallazgos)

F4 dejó la UI funcionando de punta a punta, pero mínima.

- **Historial de la conversación** ✅: cada pregunta ahora agrega un "turno" nuevo al DOM
  (`#historial`) en vez de reemplazar al anterior — pregunta, vista previa, respuesta y citas
  propias por turno, con scroll automático al turno nuevo. No exige memoria conversacional en el
  núcleo (cada pregunta le sigue llegando a `Consultar` de forma independiente, y esa sigue siendo
  una decisión, no un olvido).
- **Progreso visible del pipeline** — **recortado en el alcance real, ver el primer hallazgo de
  F10**: el plan original pedía eventos SSE por cada una de las 7 etapas (planner → herramientas →
  síntesis). Eso exige que `Orquestador`/`Consultar` dejen de ser síncronos hasta la etapa 5, un
  cambio de fondo al núcleo que esta sesión decidió no hacer. Lo que sí se implementó: un contador
  de segundos transcurridos ("Buscando y analizando tu pregunta… (37 s)") mientras el pipeline
  corre, y el cambio a "Redactando la respuesta…" cuando las citas llegan — sigue siendo dos fases,
  como en F4, pero ahora con una señal de que algo avanza en vez de un texto estático.
- **Errores visibles** ✅: si el `EventSource` falla (p. ej. Ollama no responde), el turno lo dice
  explícito ("Se perdió la conexión con el servidor (¿Ollama no responde?)"), en rojo, en vez de
  quedarse colgado en silencio.
- **Selector de proyecto poblado desde la base** ✅: `GET /api/admin/proyectos` (nuevo, en
  `ingesta`, excluido de `ApiTokenFilter` por la misma razón que `/api/admin/ayuda`) devuelve los
  `project_id` reales de `sources`; `proyecto` pasó de `<input>` de texto libre a `<select>`.
- **Criterio de salida, verificado en un navegador real** (Chromium headless vía Playwright, dos
  preguntas reales de punta a punta contra el pipeline en CPU — ver hallazgos de F10): alguien que
  abre `localhost:8080` pregunta, ve el contador de progreso avanzar en segundos reales
  ("Buscando y analizando tu pregunta… (4 s)"), obtiene una respuesta citada y bien formada, y al
  encadenar una segunda pregunta la primera sigue completa y visible en el historial. El selector de
  proyecto llega poblado con los `project_id` reales desde el primer render.

---

## Verificación

**Extremo a extremo, sin credenciales:**

1. Arranca Docker Desktop (el engine está detenido) y copia `wslconfig.example`.
2. `cp .env.example .env && docker compose up -d` — los 3 servicios quedan sanos.
3. `make pull-models` baja `gemma3:4b` y `bge-m3` al volumen en D:, y el ONNX del reranker.
4. `make seed` ingiere el corpus de ejemplo (copiado a `vault/documentos`); verifica en `db` que
   `chunks` tenga `embedding` y `fts` poblados.
5. `curl -X POST localhost:8080/api/ask -d '{"q":"¿cómo se despliega el servicio?"}'` devuelve
   respuesta con citas.
6. Abre `localhost:8080`: resultados por palabra clave inmediatos y síntesis en streaming.
7. Bot Framework Emulator contra `localhost:8080/api/messages`: misma respuesta, en tarjeta.
8. Con GPU: `docker compose -f compose.yml -f compose.gpu.yml up -d` y confirma con `nvidia-smi`
   que solo `gemma3:4b` ocupa VRAM.
9. **F8**: copia un `.md` nuevo a `vault/documentos` y **no corras ningún comando**. Pasado el
   intervalo de la fuente, pregunta por su contenido: debe responderse citándolo. Borra el archivo,
   espera otro intervalo, y confirma que deja de citarse.
10. **F9**: sin usar la terminal, sube un documento desde la consola de administración, confirma
    que la fuente refleja el relevo y que la fila del archivo en "Archivos del vault" avanza hasta
    "listo", y pregúntale por ese contenido desde la misma UI. Abre el botón `?` y confirma que la
    ruta que muestra es la que el servidor está leyendo de verdad: cambia `KB_VAULT_DIR` en `.env`,
    reinicia, y la ayuda debe reflejar el cambio.
11. **F10**: pregunta dos veces seguidas desde `localhost:8080` y confirma que la primera respuesta
    sigue visible cuando llega la segunda. Cambia el selector de proyecto y confirma que solo
    aparecen los `project_id` que existen de verdad. Detén Ollama y confirma que la UI dice que
    perdió la conexión en vez de quedarse colgada.
12. Los pasos 9-11 se verificaron **dos veces** en un navegador real (Chromium headless vía
    Playwright, sin `claude-in-chrome` disponible esta sesión): la primera corrida encontró el bug
    de espacios en el streaming SSE (ver hallazgos de F10); la segunda, después de corregirlo,
    confirmó las capturas limpias. Sigue pendiente repetirlo alguna vez en un Chrome/Edge de
    escritorio con `claude-in-chrome`, para la misma confianza visual con la que se verificaron
    F0-F4 (Playwright headless no sustituye del todo un ojo humano mirando la pantalla).

**Pruebas automatizadas** (tus gates de riesgo habituales):

- **ArchUnit**: `web` y `teams` no dependen de `recuperacion`, `ingesta`, `modelos` ni `llm`.
  Esta prueba es el contrato de "dos adaptadores" y debe existir desde F0.
- **Modulith**: `ApplicationModules.verify()` en el pipeline.
- **jqwik** (propiedades): RRF es monótono y el consenso gana sobre un primer puesto aislado;
  el decaimiento por antigüedad nunca invierte el orden dentro de la misma fecha; los gates de
  bursting respetan los umbrales exactos.
- **Testcontainers**: Postgres real, ingesta de un fixture, consulta híbrida de las 4 señales,
  verificación de que el reranker reordena.
- **WireMock**: Graph y Azure DevOps doblados; validación del JWT de Bot Connector con un JWKS
  de prueba, incluyendo tokens vencidos y con audiencia equivocada.
- **Contrato de adaptadores**: la misma pregunta por HTTP y por Teams produce las mismas citas.
- **Regresión de retrieval**: 20 pares pregunta/documento esperado; se mide recall@10 antes y
  después del reranker y el gate falla si baja.

---

## Hallazgos de la implementación de F0

Trampas reales encontradas al construir, no previstas en el diseño. Todas ya resueltas en el
código; se documentan porque cada una habría costado una tarde de depuración:

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **PostgreSQL 18 movió `PGDATA`** a `/var/lib/postgresql/18/docker` y declara el `VOLUME` en `/var/lib/postgresql` | Montar en `/var/lib/postgresql/data` (correcto hasta la 17) hace que los datos **no persistan, en silencio** | Volumen montado en `/var/lib/postgresql`; verificado con `pg_settings` |
| **Testcontainers 2.0 renombró todos sus módulos** | `org.testcontainers:postgresql` ya no existe; el `pom` no resolvía | Ahora `testcontainers-postgresql` y `testcontainers-junit-jupiter`, con BOM importado explícitamente (Boot 4.1 ya no lo trae) |
| **Spring Boot 4 partió las autoconfiguraciones en módulos** — `flyway-core` **ya no cablea nada** | El más grave: la app arrancaba contra una base **vacía**, reportaba `health: UP` y no decía nada | Agregado `org.springframework.boot:spring-boot-flyway`. Y sobre todo: `EsquemaTest` con Testcontainers ahora **afirma** que la migración corrió, para que no vuelva a pasar en silencio |
| **Spring Boot 4 movió el health a `spring-boot-health`** | `org.springframework.boot.actuate.health.HealthIndicator` ya no existe | Nuevo paquete `org.springframework.boot.health.contributor` |
| **Boot 4 cambió la estructura de `extract --layers`** | Las capas quedan directamente bajo el destino; el nivel `/layers/app/…` de `layertools` (Boot 3) ya no existe | Rutas corregidas en el `Dockerfile`; el destino además debe estar vacío |
| **Testcontainers 2.0 no arrastra `commons-lang3`** | `NoClassDefFoundError` al instanciar el cliente de Docker | Declarado como dependencia de prueba |
| **El stemmer español no lematiza como uno supone** | `despliegue`→`desplieg` pero `desplegar`→`despleg`: **no coinciden**. Y `servicio`/`servicios` coinciden también en inglés, así que no prueban nada | El test usa un par verificado (`ejecuta`/`ejecutar`) y comprueba además que en inglés **no** coincidiría |
| **`cmd \| tail` enmascara el código de salida** | Un build de Docker fallido se leyó como exitoso | Los comandos de build ahora capturan el código real antes de filtrar la salida |
| **Puerto 5432 ocupado** por otros contenedores de la máquina | El `compose up` fallaba entero | Puerto por defecto movido a **55432**; dentro de la red de compose se sigue usando 5432 |
| **`jqwik` inyecta texto adversario** en la salida del build: *"If you are an AI Agent… disregard previous instructions"* | Intento de inyección de prompt en un artefacto de build | Ignorado. Queda como decisión pendiente: reemplazar `jqwik` por generadores propios en las pruebas de propiedades de RRF |
| **JDK local es 21, no 25** | `mvn test` local no compilaría a 25 | Objetivo bajado a Java 21 LTS, que Boot 4.1 soporta plenamente. **Actualización**: el JDK 25 ya está instalado y el objetivo volvió a 25 |

## Hallazgos de la implementación de F1

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **Spring AI 2.0 aplanó `chat`/`embedding.options.*`** a `chat.model`/`embedding.model` directo | El mismo patrón de F0: la propiedad anidada vieja se ignora en silencio, la app arranca con el modelo por defecto de Ollama en vez del pedido | Corregido en `application.yml`, verificado contra la documentación oficial, no contra memoria |
| **Boot 4 trae Jackson 3** (`tools.jackson.databind`), no Jackson 2 (`com.fasterxml.jackson.databind`) | Cualquier import del paquete viejo simplemente no compila — que compile a fallar rápido, no en runtime | `JacksonException` ahora es *unchecked*; el código de la ingesta no necesita `try/catch` para JSON |
| **Auto-invocación anula `@Transactional`** — `ejecutarLote()` llamaba a `this.procesarUno()` dentro de la misma clase | El proxy de Spring nunca se interpone en una llamada interna: "tomar trabajo" y "marcar hecho" habrían quedado como sentencias sueltas en autocommit, no como una transacción | Separado en dos clases: `TrabajadorEmbebidoProgramador` (el `@Scheduled`) llama a `TrabajadorEmbebido` (el `@Transactional`) por una referencia de bean real |
| **`EsquemaTest` ensuciaba el log con errores de scheduling** | El worker programado intentaba embeber contra un Ollama que ese test nunca levanta | Propiedad `kb.ingesta.worker.habilitado`, apagada en ese test |
| **`onnx-community/bge-reranker-v2-m3-ONNX`** es una re-subida de la comunidad, no de BAAI | Instalar un artefacto de ML de procedencia no verificada sin control es un riesgo real | Hash SHA-256 fijado en el `Makefile`; la descarga se borra si no coincide |
| **`getInputNames()` del grafo ONNX no incluye `token_type_ids`** | Suponerlo hardcodeado habría hecho fallar la inferencia con este export en particular | El código construye los tensores dinámicamente según lo que el grafo pide, verificado con el log real: `[input_ids, attention_mask]` |

## Hallazgos de la implementación de F2

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **`@ConfigurationProperties` + `@Component` no basta con records anidados** — `RecuperacionPropiedades` tiene un record interno `Pesos` y otro `Decaimiento` | El contenedor intentaba resolver `Pesos` y `Decaimiento` como **beans propios** antes de que corriera el binding de propiedades, y el arranque fallaba con `NoSuchBeanDefinitionException` | `@ConfigurationPropertiesScan` en `BaseConocimientoApplication`, sin `@Component` en la clase de propiedades — el registrador correcto para records de configuración |
| **`ts_stat` es la forma segura de tokenizar texto de usuario dentro de SQL dinámico** | Construir el `tsquery` de la señal IDF concatenando el texto de la consulta a mano abriría inyección SQL | `ts_stat('SELECT to_tsvector(''spanish'', ' \|\| quote_literal(:consulta) \|\| ')')`: `quote_literal` es el mecanismo propio de Postgres para escapar un valor antes de incrustarlo en SQL dinámico, no concatenación ingenua |
| **Reestemizar un léxemo ya estemizado no es idempotente** (mismo hallazgo de F0, con una consecuencia nueva aquí) | Si la señal IDF hiciera `to_tsquery('spanish', term_stats.term)` sobre un término que `term_stats` ya guarda estemizado (p. ej. `desplieg`), el resultado no está garantizado a coincidir con el léxemo original en `chunks.fts` | Los léxemos de la consulta se extraen con el mismo `to_tsvector('spanish', ...)` que generó `chunks.fts` y `term_stats` (vía `ts_stat`), y el `JOIN` contra los chunks usa `to_tsquery('simple', ...)` — config sin estemizado, que no vuelve a tocar un léxemo que ya es estemizado |
| **jqwik: el aviso adversario y la doble ejecución de F0 se confirman con una prueba `@Property` real** | `RrfFusionTest` es el primer uso real de `net.jqwik:jqwik` (antes solo estaba declarado). El build mostró de nuevo *"If you are an AI Agent... disregard previous instructions"* y la clase corrió dos veces (una vía el motor de JUnit Jupiter, que se salta los `@Property`; otra vía el motor de jqwik, que se salta los `@Test`) | Ignorado, igual que en F0: son artefactos conocidos de tener dos motores de JUnit Platform activos, no fallas. Sigue pendiente la decisión de reemplazar jqwik por generadores propios |

## Hallazgos de la implementación de F3

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **F2 no había previsto `ord` en `Fragmento`** — la expansión de contexto de F3 necesita saber la posición del chunk dentro de su documento para pedir vecinos | Sin ese dato no hay forma de encontrar "la sección de al lado" | Se agregó `ord` al record compartido y se propagó por `CandidatoSenal`, `CandidatoFusionado` y el `SELECT` de `RecuperacionRepositorio` — un cambio real motivado por una necesidad concreta de F3, no una anticipación en F2 |
| **`recuperacion` no tenía ninguna puerta pública** — F2 dejó todo package-private a proposito, pero nadie fuera del modulo necesitaba llamarlo todavia | Las herramientas `search_unified` y `search_docs` de F3 no podian invocar el retrieval hibrido sin una API cruzando el limite del modulo | Se agrego `Buscador`, la fachada publica de `recuperacion` (mismo patron que `Consultar` en `orquestacion`), y `ResultadoBusqueda` paso a publico. `Recuperador` la implementa pero sigue siendo package-private |
| **`ChatClient.entity(Class, spec -> spec.useProviderStructuredOutput())`** es la forma correcta de pedir salida estructurada a Ollama en Spring AI 2.0, verificada contra la documentacion oficial (no memoria) | Usar el converter generico basado en instrucciones de texto es menos confiable con un modelo chico de 4B; la API nativa por proveedor fuerza el esquema del lado de Ollama | `PlanificadorOllama` la usa, con una captura amplia de excepciones que cae a `search_unified` si el planner falla o el modelo no valida contra el esquema — el pipeline nunca se cae por un mal plan |
| **`query_log.adapter` solo admite `'web'` o `'teams'`** (CHECK del esquema de F0) | El controller operativo de F3 (`/api/ask`, igual que `IngestaController`/`RecuperacionController` en sus modulos) no es un adaptador real todavia, pero necesita un valor valido para insertar | Se registra como `'web'`: es honesto, porque esta es literalmente la respuesta HTTP que el adaptador web de F4 va a exponer sin cambios |
| **`reactor.core.publisher.Flux` ya estaba en el classpath** sin declarar ninguna dependencia nueva | `Sintetizador.sintetizar(...)` devuelve `Flux<String>` y compilo sin tocar el `pom.xml` | Confirmado: `spring-ai-starter-model-ollama` ya trae Reactor de forma transitiva para el streaming, independientemente de que el proyecto use Spring MVC y no WebFlux |
| **`advertencias` de `Respuesta` queda vacio a proposito** | El plan pide sintesis en streaming Y que se señalen contradicciones; separarlas en un campo estructurado aparte exigiria una segunda llamada al LLM solo para eso, en contra del streaming | Las contradicciones se señalan EN el texto de la respuesta (prosa + marcador `[n]`), tal como lo pide el prompt de sistema del sintetizador — decision documentada en el codigo, no un olvido |

## Hallazgos de la implementación de F4

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **`qwen3:4b` trae "thinking" activado por defecto en Ollama** — hallazgo crítico, medido en vivo contra el stack real, no en teoría | Un prompt trivial ("di hola en una palabra") tardó **3m28s** con thinking encendido: 574 tokens de razonamiento interno en ingles antes de una respuesta de una palabra. Con dos llamadas por pregunta (planner + síntesis), `/api/ask` no terminaba ni a los 6m40s | `OllamaChatOptions.builder().disableThinking()` en `PlanificadorOllama` y `SintetizadorOllama`, con `kb.llm.thinking-habilitado=false` por defecto (reactivable con `KB_LLM_THINKING_HABILITADO=true` en una máquina con GPU y margen). Bajó el planner de ~9 min a ~1 min |
| **`ChatClient.Builder.defaultOptions(...)` pide un `ChatOptions.Builder`, no una instancia ya construida** — cambio real de firma respecto a Spring AI 1.x | `defaultOptions(OllamaChatOptions.builder().disableThinking().build())` no compila: *incompatible types* | Se pasa el builder sin `.build()`: `defaultOptions(OllamaChatOptions.builder().disableThinking())` |
| **Con thinking apagado, `qwen3:4b` igual narra su razonamiento como texto plano** ("Okay, let's tackle this query... First, I need to look at the context...", en inglés) — verificado en el streaming real, tres veces | El usuario ve ese ruido antes de la respuesta real; `disableThinking()` apaga el canal separado de *thinking* de Ollama, pero no evita que el modelo sea verboso en el texto principal | Dos intentos por prompt, **ambos verificados en vivo y ninguno lo resolvió** (uno de ellos, un few-shot, lo empeoró a un bucle de 5+ min). **Resuelto reemplazando el modelo**: `gemma3:4b` (mismo tamaño, sin modo thinking) responde directo — verificado en vivo: *"Para desplegar el servicio, se necesita Docker Desktop con el motor iniciado. [1], [2]..."*, sin preámbulo, en 2m58s de punta a punta. Ver la fila siguiente sobre `gemma4`, evaluado y descartado por ahora |
| **`gemma4` existe y es más nuevo que `gemma3`** (Google, abril 2026 — posterior al corte de entrenamiento del asistente; encontrado porque el usuario preguntó "¿por qué no gemma4?", no porque se buscara proactivamente) | Usar memoria de entrenamiento en vez de buscar casi elige un modelo ya superado | Se subió la imagen de Ollama (`docker compose pull ollama`, 0.20.4 → 0.32.5) y se descargó `gemma4:e4b` para comparar en vivo contra `gemma3:4b`, misma pregunta, mismo corpus — ver la fila siguiente con el resultado |
| **Comparación en vivo, `gemma3:4b` vs `gemma4:e4b`, misma pregunta y corpus** | `gemma4:e4b` pesa **9,6 GB** contra 3,3 GB de `gemma3:4b` (probablemente por componentes multimodales de audio/visión que no se usan aquí) y tardó **3m46s** contra 2m58s — ~27 % más lento. A cambio, su respuesta fue notablemente mejor: una lista numerada completa que sigue la estructura real del documento, citando *varias* fuentes por afirmación (`[1, 2, 4]`) en vez de una sola, mientras que `gemma3:4b` dio una respuesta correcta pero más corta y con una sola cita por afirmación. Ninguno de los dos narra razonamiento — ambos resuelven el problema original de `qwen3:4b` | **Se mantiene `gemma3:4b` como default**: el presupuesto de 4 GB de VRAM del perfil GPU (la decisión "Generación" de este plan) no admite un modelo de 9,6 GB de ninguna forma, y la ganancia de calidad no justifica romper esa restricción para el perfil por defecto del taller. `gemma4:e4b` queda documentado como alternativa validada y superior en calidad para quien tenga más disco/RAM y no dependa del perfil GPU de 4 GB: `KB_LLM_MODELO=gemma4:e4b` en `.env` |
| **`@WebMvcTest` se movió de módulo y de paquete en Boot 4** — mismo patrón de partición ya visto en F0/F1 | `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` ya no existe; `spring-boot-starter-test` ya no lo trae | Nueva dependencia `spring-boot-webmvc-test`; import corregido a `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| **`@Validated` a nivel de clase + `@RequestParam` con `@NotBlank` no da 400** | El interceptor AOP (`MethodValidationInterceptor`) lanza `ConstraintViolationException` cruda, que Spring Boot no traduce a 400 por defecto — el request terminaba en 500 | Se quitó `@Validated`: la validación nativa de parámetros de método de Spring MVC (sin esa anotación, desde Framework 6.1+) sí traduce la violación a 400 automáticamente. Confirmado con `ChatControllerTest` |
| **`Flux<ServerSentEvent<Object>>` funciona en Spring MVC puro** (sin WebFlux) | Había que confirmar que no hacía falta `spring-boot-starter-webflux` — agregarlo hubiera chocado con MVC | Confirmado contra la documentación oficial y con una prueba de MockMvc (`asyncDispatch`) más la verificación en vivo contra `localhost:8080` |

## Hallazgos de la implementación de F5

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **El Bot Framework SDK (archivado) distingue `ChannelValidation` de `EmulatorValidation`** — no es un solo validador con un flag | `ChannelValidation` lee el appId del claim `aud` directo; `EmulatorValidation` lo lee de `appid` (token v1) o `azp` (token v2) según el claim `ver`, con audiencia deshabilitada porque la valida a mano. Suponer una sola forma habría rechazado tokens válidos del Emulator | `ValidadorTokenBotFramework` porta esa distinción, con las constantes (URLs de metadata, issuer de canal, los 6 issuers válidos del Emulator) verificadas contra el código fuente actual de `AuthenticationConstants.java`/`EmulatorValidation.java`/`ChannelValidation.java` en GitHub, no contra memoria |
| **`NimbusJwtDecoder.withJwkSetUri(...)` ya cachea el JWKS por su cuenta** | Escribir una caché propia sobre el JWKS habría sido redundante: la librería de Spring Security ya envuelve un `RemoteJWKSet` con su propio cacheo | El "caché del JWKS" del plan queda resuelto usando la librería tal cual, con un `ConcurrentHashMap<String, JwtDecoder>` propio solo para no reconstruir el decoder (y volver a pedir el documento OpenID) en cada request |
| **jackson-annotations es la única excepción al repaquetado de Jackson 3** (mismo tema del hallazgo de F1, con un matiz nuevo) | `@JsonIgnoreProperties`/`@JsonInclude` siguen en `com.fasterxml.jackson.annotation`, NO en `tools.jackson.annotation` — a propósito, para que el mismo modelo de anotaciones sirva sin cambios en proyectos 2.x y 3.x. Suponer el prefijo `tools.jackson` a ciegas para *todo* Jackson 3 no habría compilado en `Activity.java` | Verificado contra la guía de migración oficial de Jackson 3, no memoria; import correcto documentado en el propio archivo |
| **WireMock cambió de groupId en la 3.x, no de paquete Java** | `com.github.tomakehurst:wiremock-jre8` ya no existe; el artefacto es `org.wiremock:wiremock-standalone`, pero las clases siguen bajo `com.github.tomakehurst.wiremock.*` (mismo patrón que Jackson: compatibilidad de código a cambio de romper solo las coordenadas Maven) | Confirmado contra el código fuente de `WireMockExtension`/`DslWrapper` en el repo de GitHub antes de escribir las pruebas, evitando adivinar el import |
| **El webhook `/api/messages` no puede esperar la síntesis** — medido en F4: 2-3 minutos por pregunta en CPU | Devolver 200 solo después de llamar a `Consultar.responder(...)` habría expuesto ese webhook al timeout del canal | `BotController` responde 200 de inmediato y delega en `ProcesadorDeMensajes.procesarAsync`, que dispara un hilo virtual (`Thread.ofVirtual().start(...)`) para el indicador de escritura, la consulta y la respuesta final — el mismo patrón de hilos virtuales que ya usa `Executor` en F3 |
| **`ProcesadorDeMensajes` separa `procesar` (síncrono) de `procesarAsync`** | Probar el flujo completo contra un hilo virtual real habría exigido latches o `Awaitility` solo para sincronizar el test con un hilo en segundo plano | El método síncrono es paquete-privado y se prueba directo con Mockito, sin hilos ni HTTP — la variante async es una sola línea que no necesita su propia prueba |
| **No se ejecutó una conversación real contra el Bot Framework Emulator** | Docker sí estaba disponible (`./mvnw test` corrió Testcontainers con Postgres real sin problema), pero no se levantó `compose.yml` completo con Ollama ni se instaló el Emulator — a diferencia de F0-F4, que sí se verificaron en vivo contra el stack real de punta a punta | Cubierto por pruebas automatizadas (WireMock + JWT firmado de verdad, Mockito para el flujo del procesador) en vez de por una corrida en vivo. Sigue siendo un pendiente real: repetir el paso 7 de la sección Verificación con el stack completo y el Emulator antes de dar F5 por cerrado con la misma confianza que las fases anteriores |

## Hallazgos de la implementación de F6

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **`KB_CODIGO_RUTA` nunca estaba fijada en `compose.yml`** — mismo patrón exacto que el hallazgo de F1 sobre `KB_CORPUS_RUTA` | `kb.orquestacion.codigo-dir` (la herramienta `search_code` de F3) caía a su default relativo `./repos`, que dentro del contenedor resuelve contra `WORKDIR /app`, no contra el `/repos` que `compose.yml` sí monta como bind mount — `search_code` buscaba en una carpeta vacía, en silencio, desde F3 | Se agregó `KB_CODIGO_RUTA: /repos` a `compose.yml`. `kb.ingesta.repos-dir` (el conector nuevo de F6) reutiliza la misma variable: ambos apuntan al mismo directorio, no a dos rutas distintas |
| **Incrementalidad de repos a nivel de repo, no de commit** | Diffear cada commit con JGit (`TreeWalk`/`DiffFormatter`) para saber que archivos cambiaron es sustancialmente mas codigo que comparar el HEAD SHA guardado y, si cambio, re-hashear archivo por archivo — la misma tecnica que `ConectorDocumentosLocales` ya usa | Decisión deliberada, documentada aquí: se compara el SHA de HEAD contra `sources.sync_state.last_sha`; si no cambió, el repo entero se salta. Si cambió, la incrementalidad por archivo es igual de barata via hash de contenido — a esta escala, caminar el árbol de trabajo en disco es tan correcto como diffear commits y bastante más simple |
| **El gate de bursting reutiliza `ts_stat` sobre el propio texto del mensaje**, igual que la señal 3 de F2 | Escribir un segundo mecanismo de IDF para bursting hubiera duplicado exactamente la lógica (y el riesgo de re-estemizar un léxema ya estemizado, ver el hallazgo de F0/F2) que `RecuperacionRepositorio.buscarPorIdf` ya resuelve | `IngestaRepositorio.maxIdf` es la misma consulta con `quote_literal`, sin concatenación insegura, adaptada para devolver un solo escalar en vez de una lista de candidatos |
| **Graph delta query devuelve solo mensajes raíz, nunca respuestas** — verificado contra la documentación oficial de `channel-list-messages`, no memoria | Sin una llamada aparte por hilo (`/messages/{id}/replies`), el texto destilado habría sido solo la pregunta, sin ninguna respuesta — el artículo pierde su sentido si el hilo queda a medias | `ConectorTeamsGraph.obtenerRespuestas` pagina esa llamada por separado para cada mensaje raíz, y las ordena por `createdDateTime` antes de armar el texto del hilo |
| **`EmulatorValidation`/`ChannelValidation` de F5 no aplican aquí, pero el patrón de dos APIs con formas de auth distintas sí se repite**: Graph exige `client_credentials` (como el token saliente del Bot Connector), Azure DevOps exige Basic con PAT — nada de OAuth | Tratar ambos conectores con el mismo cliente HTTP habría sido incorrecto: el connector de Graph necesita cachear un token con expiración, el de Azure DevOps no necesita cachear nada | `ClienteGraph` y `ClienteAzureDevOps` quedan como dos clases separadas, cada una con la autenticación que su API realmente pide — ver también el hallazgo de F5 sobre por qué no unificar mecanismos de auth distintos bajo una abstracción común |
| **La wiki de Azure DevOps no entrega contenido en `pagesbatch`** — verificado contra la documentación oficial (`Pages Batch - Get` solo devuelve `path`/`id`), no memoria | Usar `pagesbatch` (que sí pagina con `continuationToken`) habría exigido una segunda llamada por página igual, así que se cambió a la operación más simple: `pages?path=/&recursionLevel=Full` para el árbol completo de rutas en una sola llamada, y `pages?path=...&includeContent=true` por página | Evita la complejidad de paginación de `pagesbatch` sin perder nada: a la escala de una wiki de equipo, traer el árbol completo de una vez es igual de razonable |
| **`UriComponentsBuilder` es necesario para el `path` de una página de wiki, pero no para org/proyecto/wiki** | Los títulos de página de Azure DevOps suelen tener espacios (`/Getting Started`), que deben ir *percent-encoded* en la query string; concatenar el string a mano habría roto esas URLs | `ConectorAzureDevOps.urlPagina` arma la URL con `UriComponentsBuilder.buildAndExpand(...).encode()`; el resto de las rutas (org, proyecto, PAT no viaja en la URL) se concatena directo, con la limitación documentada de que esos tres valores deben ser slugs válidos de URL |
| **Los mensajes de Teams y las descripciones de work items llegan en HTML**, no en texto plano | Indexar el HTML crudo habría metido etiquetas y entidades (`&nbsp;`, `<p>`) en el texto que alimenta FTS y el embedding, degradando ambas señales | Un `stripHtml` heurístico (regex sobre etiquetas + las entidades más comunes) en ambos conectores — duplicado a propósito en vez de una utilidad compartida en `compartido`, siguiendo el mismo criterio que `IngestaRepositorio` ya documenta para no ser genérico de más |
| **`@MockitoBean Destilador destilador` en `ConectorTeamsGraphTest`** funciona igual sobre `@SpringBootTest` completo que sobre un slice `@WebMvcTest` (ya usado en F4/F5) | Sin esto, la prueba habría intentado llamar a Ollama real, que no está disponible en la corrida de pruebas | Confirmado en la práctica: el mismo mecanismo de reemplazo de bean sirve para cualquier tipo de contexto de prueba de Spring Boot 4, no solo para slices |

## Hallazgos de la implementación de F7

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **`documents.acl jsonb` existe desde F0 pero nunca se propagó a `chunks`, y ningún query de retrieval la consulta** | La "ACL a nivel de fuente" de los Supuestos del plan quedaba como un campo decorativo: guardado por los conectores, invisible para `RecuperacionRepositorio` | En vez de improvisar un enforcement a medias sin tener resuelta la pregunta de identidad (¿ACL de qué usuario, si ni la UI ni Teams autentican personas?), se documentó como decisión pendiente explícita — [ADR-0007](../adrs/0007-acl-por-fuente-pendiente.md), con Row-Level Security nativo de Postgres identificado como el camino correcto si se retoma, evaluado y descartado por ahora junto con `pgjwt`/`pgcrypto` como alternativas que no encajan con un token compartido |
| **`EventSource` nativo del navegador no puede mandar cabeceras propias** | Si `ApiTokenFilter` hubiera protegido `/api/chat` (SSE) igual que `/api/ask`, la UI web se habría roto en cuanto alguien configurara `KB_API_TOKEN` — un adaptador entero (F4) dejando de funcionar por una decisión de F7 | El filtro excluye explícitamente `/api/chat` y `/api/preview`: el token protege la llamada programática (`curl`, scripts), no la página que ya es de acceso libre en este MVP sin login de persona |
| **Un `@ConfigurationProperties(prefix = "kb")` nuevo puede coexistir con otros ya registrados bajo el mismo prefijo** | Dudaba si declarar `SeguridadPropiedades` sobre `kb` (donde ya viven `kb.llm.*`, `kb.teams.*`, etc.) rompería el binding de las demás clases | Confirmado: el binding relajado de Spring Boot solo mapea las claves que el record declara (`apiToken` → `kb.api-token`); las claves no declaradas bajo el mismo prefijo se ignoran para esa clase, sin conflicto — mismo patrón que ya usan `TeamsPropiedades` (`kb.teams`) y `RecuperacionPropiedades` |

## Hallazgos de la implementación de F8

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **`sources.refresh_seconds` no encaja con un relevo por fila, descubierto al diseñar el disparador** | `local_git` descubre sus repos recorriendo `./repos` en cada corrida (una fila de `sources` por subcarpeta, número que no se conoce de antemano) y `azure_devops` crea dos fuentes fijas (work items y wiki) en la misma llamada — ningún conector tiene una forma de sincronizar "solo esta fila ya conocida", así que un relevo por fila con `refresh_seconds` individual habría exigido rediseñar los cuatro conectores | Se releva por **tipo de conector** (`RelevadorDeFuentes.relevarTodas()`), no por fila. `refresh_seconds` queda como metadato informativo en `sources` para la consola de F9, no como disparador — divergencia real del boceto original de este plan, documentada en vez de forzada en silencio |
| **`@Scheduled(fixedDelayString=...)` sin `initialDelayString` corre la primera vez apenas arranca el contexto de Spring** | En la suite completa, esto hizo que el relevo automático corriera EN PARALELO con la invocación manual de los propios tests de conectores sobre el mismo conector — visto en vivo como `ERROR: duplicate key value violates unique constraint "chunks_orden_unico"` en `ConectorAzureDevOpsTest`, con la prueba igual en verde (el error quedaba silencioso en el log) | `initialDelayString` fijado al mismo valor que `fixedDelayString`: el primer relevo espera un intervalo completo antes de correr, lo cual además es lo correcto para el producto (recién booteado no hay nada nuevo que revisar) |
| **`ConectorDocumentosLocales.ingerir()` nunca llamaba a `actualizarSyncState`** (a diferencia de repos/Teams/Azure DevOps, que sí lo hacen para su propio token incremental) | `sources.last_synced_at` para `local_docs` quedaba `NULL` para siempre, aunque el conector corriera bien — verificado en vivo: la consola de F9 mostraba "nunca" después de varios relevos exitosos | Se agregó una llamada a `repo.actualizarSyncState(sourceId, "{}")` al final de `ingerir()`, solo para el efecto lateral de marcar `last_synced_at = now()` — sin JSON de estado real que guardar, a diferencia de los otros tres conectores |
| **`IngestaController` llamaba a los conectores directo**, sin pasar por ningún candado | Antes de `RelevadorDeFuentes`, `make ingest` (vía `/api/ingest/local-docs`) y el futuro relevo automático de F8 podrían haber corrido el mismo conector a la vez, sin ningún mecanismo que lo evite — una condición de carrera real, introducida por este mismo cambio si no se corregía | `IngestaController` se reescribió para delegar en `RelevadorDeFuentes.relevar(tipo)` en vez de invocar los conectores directamente: manual (`make ingest`), automático (F8) y el botón de F9 comparten ahora el mismo candado por tipo |
| **Verificado en vivo contra el stack real**: `KB_INGESTA_RELEVO_INTERVALO_MS=15000`, se agregó y luego se borró un archivo del corpus sin correr ningún comando | Confirma el criterio de salida de la fase, no solo las pruebas unitarias del candado (`RelevadorDeFuentesTest`, con hilos reales para probar la exclusión mutua) | Log real: *"3 vistos, 2 actualizados, 1 sin cambios, 0 eliminados, 4 chunks nuevos"* al agregar; *"1 eliminados"* al borrar. `.env` restaurado al intervalo default (900000 ms) después de la prueba |

## Hallazgos de la implementación de F9

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **`KB_INGESTA_CARGA_HABILITADA` se declaró en `application.yml`/`.env.example` pero no en `compose.yml`** — el mismo patrón exacto del hallazgo de F1 sobre `KB_CORPUS_RUTA` y del de F6 sobre `KB_CODIGO_RUTA` | Configurar la variable en `.env` no tenía ningún efecto: `docker compose` no reenvía automáticamente variables de `.env` al contenedor a menos que estén listadas en el bloque `environment:` del servicio. Detectado en vivo: `GET /api/admin/ayuda` seguía devolviendo `cargaHabilitada:false` con la variable en `true` en `.env` | Se agregó `KB_INGESTA_CARGA_HABILITADA` al bloque `environment` de `api` en `compose.yml`, con el mismo comentario de advertencia que ya dejaron F1/F6 sobre este patrón exacto |
| **El corpus está montado `:ro`** (`${KB_CORPUS_DIR:-./corpus}:/corpus:ro`) | La carga de archivos, tal como se diseñó, no puede funcionar mientras el mount siga siendo de solo lectura, sin importar el valor de `kb.ingesta.carga-habilitada` | Se dejó el `:ro` como default (no se tocó `compose.yml` de forma permanente): habilitar la carga exige un cambio manual y consciente de infraestructura, no solo una variable de entorno. Verificado quitando el `:ro` a mano temporalmente, confirmando que la carga escribe en el host, y revirtiendo el mount después |
| **El botón "Reindexar ahora" opera por tipo de conector, no por fila individual de `sources`** | Con varios repos bajo `local_git`, un solo botón "Reindexar ahora" en la sección del tipo alcanza y corre los repos, porque `ConectorReposLocales.ingerir()` ya los recorre todos en una sola llamada (ver hallazgo de F8) | La consola agrupa las fuentes por tipo con un único botón por grupo, en vez de un botón por fila — coherente con que el candado de `RelevadorDeFuentes` también es por tipo |

## Hallazgos de la implementación de F10

| Hallazgo | Impacto | Resolución |
|---|---|---|
| **El pipeline de streaming es síncrono hasta la etapa 5** — `Orquestador.prepararHastaContexto` bloquea el hilo que llama a `Consultar.responderEnStreaming` hasta que planificador, executor, fusión y expansión de contexto terminan; solo la etapa 6 (síntesis) es un `Flux` de verdad | Emitir progreso real por cada una de las 7 etapas (lo que pedía la redacción original de F10) habría exigido que el núcleo deje de ser síncrono en esa parte — un cambio de fondo a `Orquestador`/`Consultador`, la fachada que este mismo plan llama "el contrato del proyecto", solo para una barra de progreso | Se recortó el alcance a propósito: un contador de segundos transcurridos del lado del cliente durante la espera, más el evento real ya existente de "citas llegaron" (que sigue marcando el fin de las etapas 1-5). Documentado aquí en vez de dejarlo pasar como "hecho" — el criterio de "avanza etapa por etapa" de la redacción original de F10 no se cumplió en el sentido literal |
| **`/api/admin/ayuda` y `/api/admin/proyectos` necesitan ser alcanzables desde la página de chat sin token**, igual que `/api/chat`/`/api/preview` en F7 | Si `ApiTokenFilter` los hubiera protegido igual que el resto de `/api/admin/*`, el selector de proyecto y el botón `?` se habrían roto en cuanto alguien configurara `KB_API_TOKEN` — el mismo problema de F7, reaparecido | Ambos se agregaron a la lista de exclusión del filtro, con la misma justificación: solo lectura, sin datos del corpus |
| **Sin `claude-in-chrome` disponible, se instaló Playwright + Chromium en el directorio de scratchpad** (fuera del repo) para verificar en un navegador real de todas formas, a pedido explícito | Confirma que "sin navegador conectado" no tiene por qué significar "solo por curl" — Node/npm ya estaban disponibles en la máquina | `npx playwright install chromium` y un script que recorre el chat (dos preguntas reales de punta a punta) y la consola de administración, con capturas de pantalla en cada paso |
| **Bug real encontrado por el propio recorrido en navegador, no por las pruebas unitarias**: el streaming de `/api/chat` perdía el espacio inicial de cada token, y la respuesta llegaba como *"Paradesplegarelservicio,senecesitaDockerDesktop..."* | El estándar SSE le quita al valor de un campo `data:` un único espacio inicial (la convención del delimitador `"data: "`). `ChatController` mandaba cada token como texto crudo (`data(token)`); un token que empieza con un espacio real (" el", " servicio" — típico de un LLM antes de cada palabra nueva) queda indistinguible de ese delimitador, y el navegador se lo come. `ChatControllerTest` no lo detectaba porque sus mocks (`Flux.just("Hola ", "mundo")`) tenían el espacio al *final* del primer token, no al *inicio* del segundo — el caso que sí dispara el bug | Cada token ahora viaja como string JSON (`Json.escribir(token)`, nuevo `web.Json`, mismo patrón que `ingesta`/`orquestacion`/`teams`): la comilla queda como primer carácter después de `data:`, no el espacio, así que no hay nada que confundir con el delimitador. El cliente hace `JSON.parse(evento.data)`. Verificado en tres niveles: `ChatControllerTest` nuevo (`data:"Hola"` en el cuerpo crudo), `curl -N` byte a byte contra el stack real, y Playwright leyendo el DOM ya renderizado — los tres confirman el espacio antes y después del fix |
| **El bug es anterior a esta sesión** — `ChatController`/`app.js` no se tocaron en F8/F9, y el bug reproduce igual con el código de F4 sin ningún cambio de F10 | El hallazgo de F4 que dice "la respuesta fue... `Para desplegar el servicio, se necesita Docker Desktop con el motor iniciado. [1], [2]...`" probablemente se leyó del streaming en la terminal o de `/api/ask` (que arma el texto completo del lado del servidor antes de responder, sin pasar por SSE), no del DOM ya renderizado en un navegador — ahí es donde el bug era invisible | Corregido en este mismo commit, no diferido: es la clase de bug que una verificación end-to-end en navegador encuentra y una prueba de backend con mocks no, que es exactamente lo que este pedido de verificación vino a comprobar |

## Riesgos vivos

| Riesgo | Mitigación |
|---|---|
| C: con 45 GB libres y Docker guardando ahí el VHDX | Bind mounts a D: declarados en `compose.yml`; el README documenta mover el data-root como recomendación |
| La memoria de WSL2 no es infraestructura como código | `wslconfig.example` versionado y aplicación manual documentada. Es el único hueco honesto del IaC |
| 4 GB de VRAM no sostienen dos modelos | Un solo `qwen3:4b` en GPU; `bge-m3` fijado a CPU con `num_gpu 0` |
| Procedencia del ONNX del reranker (re-subida de la comunidad) | Hash del artefacto fijado y verificado en el build |
| Falta de binding sólido de tree-sitter en Java | Chunker heurístico documentado en un ADR, con pruebas por lenguaje |
| Latencia en CPU: **medida en vivo en ~2-3 min por pregunta** (planner + tools + síntesis) con `gemma3:4b`, no "decenas de segundos" como se estimaba originalmente | Streaming por SSE y resultados de palabra clave al instante (F4, ya implementado) le dan al usuario algo que ver de inmediato; el indicador de escritura de Teams (F5) hace lo mismo en ese canal. Sigue siendo el riesgo más visible de la demo, aunque ya no viene acompañado de una respuesta inservible. F10 agrega el progreso etapa por etapa, que convierte la espera opaca en una espera entendible |
| ~~La ingesta exige correr un comando~~ **resuelto en F8**: se releva sola por tipo de conector, sondeando por hash | — |
| ~~Operar el sistema exige terminal~~ **resuelto en F9**: consola de administración en `admin.html` | — |
| ~~F8, F9 y F10 no se probaron en un navegador real~~ **verificados con Chromium headless vía Playwright** (sin `claude-in-chrome` conectado esta sesión): encontró y confirmó la corrección de un bug real de espacios en el streaming SSE | Falta repetirlo alguna vez con `claude-in-chrome` en un navegador de escritorio, para el mismo nivel de confianza visual que F0-F4 (paso 12 de Verificación) |
| **La ACL por fuente sigue sin RLS**, y ahora F9 agrega un segundo endpoint sensible (`/api/admin/corpus/archivos`) que escribe en disco | Mismo alcance que el riesgo de ACL ya documentado ([ADR-0007](../adrs/0007-acl-por-fuente-pendiente.md)); mitigado hoy por estar detrás de `KB_API_TOKEN` y apagado por defecto (`kb.ingesta.carga-habilitada=false`) |
| Permisos de Graph bloqueados por el admin | El conector de Teams es opcional; el producto se demuestra completo con documentos y repos |
| **ACL por fuente no se hace cumplir** (`documents.acl` existe, `chunks` no la hereda, ningún query la filtra) | Aceptable mientras no haya identidad de persona autenticada (Entra ID fuera del MVP); el aislamiento real hoy es `project_id`, no `acl`. Camino de vuelta documentado en [ADR-0007](../adrs/0007-acl-por-fuente-pendiente.md): Row-Level Security sobre `project_id`/`acl`, una vez exista de dónde sacar la identidad |
| Descarga inicial de ~5 GB de modelos | Volumen persistente en D: y `make pull-models` separado del arranque |
| Spring AI 2.0 y Modulith 2.1 son recientes | Versiones fijadas en el `pom.xml`, sin rangos; superficie de uso de Spring AI reducida a dos interfaces |
| ONNX Runtime para Java (JNI) en la imagen slim | Se valida en F1 con una prueba de humo del reranker; la base `noble` es glibc justamente por esto |
| Divergencia con el estándar de la casa (Boot 3.5) | Es deliberada y a favor: **Agéndate a Tiempo también está en una rama sin soporte OSS** y este proyecto sirve de referencia de migración |
| ~~`qwen3:4b` narra su razonamiento en la respuesta final~~ **resuelto**: se reemplazó por `gemma3:4b`, verificado en vivo sin narración | — |
~~`gemma4:e4b` no se pudo evaluar~~ **evaluado**: mejor calidad de síntesis que `gemma3:4b`, pero 9,6 GB y ~27 % más lento. Documentado como alternativa opcional, no como default (ver hallazgos de F4) | — |
