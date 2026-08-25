# Base de Conocimiento interna

Un RAG interno que responde preguntas sobre tus documentos, tu código, tus canales de Teams y tus
work items, con **citas verificables**. Reproduce la arquitectura que Cerebras describió en
*How we built our knowledge base*, con dos diferencias:

- En vez de Slack, **dos adaptadores**: una UI HTML/JS y un bot de Teams.
- **Costo cero**: modelos abiertos en contenedores locales. Sin APIs de pago, sin nube.

## Qué lo hace distinto de un RAG genérico

| Decisión | Por qué |
|---|---|
| **El texto crudo nunca se embebe** | Un LLM destila cada hilo en pregunta, resumen, resolución y sistemas involucrados. El embedding ancla ahí; el crudo solo alimenta la búsqueda de texto completo. |
| **Una sola tabla de embeddings** | Documentos, código, hilos y work items comparten tabla, índices e interfaz de consulta. Sin migración de datos, sin silos. |
| **Cuatro señales, no una** | Texto completo, vectorial, supresión por IDF y decaimiento por antigüedad. Cada una cubre el punto ciego de las otras. |
| **Fusión por rango, no por puntaje** | RRF con k=60: gana el consenso entre recuperadores, no un primer puesto afortunado. |
| **Cross-encoder antes de sintetizar** | La defensa contra falsos positivos que ninguna búsqueda por similitud da sola. |
| **Los adaptadores son piel** | Una prueba de ArchUnit falla el build si la UI o el bot tocan el retrieval. |

## Agentic RAG, acotado

No es "recupero top-k y genero": el pipeline decide en cada pregunta qué hacer, no siempre el mismo
camino — detalle completo en [`docs/architecture.md`](docs/architecture.md#pipeline-de-7-etapas-apiask-apichat).

- **Elige herramientas, no un camino fijo**: `Planificador` decide con salida estructurada cuáles de
  las 6 herramientas de búsqueda correr según la pregunta; `Executor` las corre en paralelo sobre
  hilos virtuales, aislando el fallo de una del resto.
- **Se auto-evalúa antes de responder**: el mejor score del cross-encoder decide si hay evidencia
  suficiente; en la zona ambigua, `VerificadorGrounding` hace un juicio aparte contra el LLM antes
  de dejar sintetizar.
- **Reintenta una vez, con la consulta reescrita**: si la búsqueda original no alcanza, `Reformulador`
  reescribe la pregunta al vocabulario probable de la fuente y repite el mismo plan de herramientas.
- **Sabe cuándo no sabe**: si tras eso sigue sin evidencia suficiente, corta con un mensaje fijo sin
  gastar la llamada de síntesis.

Es Agentic RAG **acotado**, no un agente abierto tipo ReAct: el conjunto de acciones es fijo (6
herramientas + 1 reformulación + 1 verificación) y el reintento es de una sola vuelta, no una
planificación libre sin límite.

## Requisitos

- Docker Desktop con WSL2
- ~15 GB libres en disco (5 GB de modelos + base + imágenes)
- 16 GB de RAM (recomendado 32)
- **Opcional**: GPU NVIDIA con `nvidia-container-toolkit`

Java y Maven **no** hacen falta para ejecutarlo: el build ocurre dentro de Docker. Solo los
necesitas para desarrollar (Java 21 y el wrapper `./mvnw` incluido).

## Arranque

```bash
cp .env.example .env
make up                 # levanta db, ollama y api
make pull-models        # ~5 GB, una sola vez
make health             # confirma que no falta ningún modelo
```

Abre <http://localhost:8080>. Para poblarlo con el corpus de ejemplo: `make seed`.

Si el host tiene una GPU NVIDIA (con `nvidia-container-toolkit`), `make up` la detecta sola y
levanta Ollama con la VRAM reservada — no hace falta ningún paso extra. Para dejarle toda la VRAM
a la síntesis (recomendado con 4 GB o menos):

```bash
make pin-embeddings-cpu   # deja toda la VRAM para la síntesis
# y pon en .env:  KB_EMBEDDINGS_MODELO=bge-m3-cpu
```

`make gpu-up` sigue disponible para forzar el perfil GPU si por algún motivo la detección
automática no encuentra `nvidia-smi`. `make help` lista todo lo demás.

## Perfiles de modelo

El LLM que resuelve planner, verificador de grounding y síntesis se habla por HTTP con una API
compatible con OpenAI — ver [ADR-0009](docs/adrs/0009-bonsai-8b-integracion-pospuesta.md) y
[la investigación completa](docs/investigacion-vram-y-modelo-llm.md). Quién sirve esa API varía por
perfil: Bonsai necesita un `llama-server` aparte (cuantización propia, sin soporte en Ollama);
Ministral y `gemma3:4b` se sirven los dos desde el mismo `ollama` que ya usás para embeddings
(`bge-m3`) y, si habilitás Teams, para el destilador (F6) — Ministral vía su endpoint compatible con
OpenAI (`http://ollama:11434/v1`), sin necesitar ningún contenedor propio. Tres perfiles, cada uno
con su propio `docker compose` y sus propios comandos:

| Perfil | Arranque | LLM | GPU |
|---|---|---|---|
| **Bonsai-8B** (default de `application.yml`) | `make pull-bonsai-gguf` (~1.16 GB, una vez) + `make up-bonsai` | 1-bit nativo, la mejor citación medida | Obligatoria |
| **Ministral 3B** (mejor precisión medida en el piloto: 85.3%, en ajuste activo) | `make pull-ministral` (~2 GB, una vez) + `make up-ministral` | GGUF oficial sin fork, servido por Ollama | Opcional |
| **gemma3:4b vía Ollama** (perfil original) | `make up` | Cuantizado, corre en CPU si no hay GPU | Opcional |

`make down-bonsai` / `make down-ministral` detienen cada perfil con los mismos `-f` que su `up`
correspondiente; `make down` sigue sirviendo como cierre genérico (limpia contenedores huérfanos
si vienes de cambiar de perfil). Bonsai reserva la tarjeta completa para su `llama-server`: no
combines `make pin-embeddings-cpu` con ese perfil. Ministral sí puede combinarse con
`make pin-embeddings-cpu` — comparte el mismo `ollama` que `bge-m3`, así que es el mismo ajuste que
ya usás con `gemma3:4b`. `make pull-models` (embeddings + reranker) aplica igual a los tres perfiles.

### Por qué Ministral no usa `llama-server` (y Bonsai sí)

Hasta hace poco, Ministral también levantaba su propio `llama-server` (mismo patrón que Bonsai). Se
cambió a Ollama por un bug real, medido en vivo, no por preferencia: la imagen
`ghcr.io/ggml-org/llama.cpp:server-cuda` publicada el 17/08/2026 no puede cargar el GGUF oficial de
este modelo — falla con `error loading model vocabulary: invalid gguf type for tokenizer.ggml.scores`
(arquitectura `Ministral3ForCausalLM`, muy nueva: el soporte de conversión en `llama.cpp` mainline
recién se mergeó el 21/01/2026, [PR #18972](https://github.com/ggml-org/llama.cpp/pull/18972) sobre
el [issue #17987](https://github.com/ggml-org/llama.cpp/issues/17987)). Ollama sí carga el mismo
GGUF sin problema. A diferencia de Bonsai, Ministral nunca necesitó una cuantización que Ollama no
soporte — no hay ninguna razón técnica para que dependa de un `llama-server` propio, así que sacarlo
de encima evita el bug de arriba, un contenedor menos, y sin volumen de cache propio (el modelo queda
en el mismo `${KB_DATA_DIR}/ollama` que `bge-m3`/`gemma3:4b`).

### Cambiar de perfil sin perder lo ya descargado

`down`/`up` solo borran y recrean **contenedores**, nunca `KB_DATA_DIR` ni las imágenes de Docker
cacheadas — el GGUF de Bonsai, el modelo de Ministral (en el volumen de Ollama), los embeddings y el
reranker sobreviven a cualquier cambio de perfil sin volver a descargarse. Eso se pierde solo con
comandos que este Makefile nunca ejecuta: `docker compose down -v`, `down --rmi all` o
`docker system prune`; no los uses para alternar perfiles. Ojo aparte con `docker image prune -a`:
libera espacio real, pero también se lleva imágenes que no estén en uso por ningún contenedor en ese
momento — si la corrés estando abajo el perfil Bonsai, la próxima `make up-bonsai` puede tener que
volver a bajar la imagen base de compilación.

Ejemplo, de Bonsai a Ministral y de vuelta:

```bash
make pull-bonsai-gguf   # una sola vez
make pull-ministral      # una sola vez
make pull-models        # una sola vez (embeddings + reranker, comunes a los 3 perfiles)
make up-bonsai

make down-bonsai        # para TODO el stack de ese perfil (db, ollama, docling, api, llama-server)
make up-ministral       # el modelo ya esta en el volumen de Ollama, no se vuelve a bajar

make down-ministral
make up-bonsai          # el GGUF ya está en .data/bonsai, no se vuelve a bajar
```

Con Bonsai en el medio, hace falta su `down` antes de levantar otro perfil: reserva la GPU completa
para su propio `llama-server`, así que dejarlo corriendo mientras levantás otro deja un contenedor
huérfano compitiendo por la misma VRAM (riesgo real de OOM en una GPU de 4 GB). Ministral no tiene
ese problema — comparte el `ollama` de siempre, así que alternar hacia o desde ese perfil es más
liviano. `db`, `ollama` y `docling-serve` sí se detienen y se vuelven a crear en cada cambio de
perfil, pero sus datos viven en volúmenes de `KB_DATA_DIR` que ese `down` no toca — tarda segundos,
no descarga nada de nuevo.

### Por qué `--build` no repite el build completo cada vez

`up` y `up-bonsai` corren `docker compose ... up --build` (`up-ministral` no reconstruye nada — ya
no hay ningún servicio propio con imagen para ese perfil), pero eso solo le pide a Docker que
**revise** si algo cambió — con el cache de capas intacto, un `make down` seguido de
`make up-bonsai` reconstruye `api` (Maven) y `llama-server` (el fork CUDA de Bonsai) en unos pocos
segundos, no en los ~15-20 minutos que tarda la primera vez (medido en vivo: 4.4s y 3.2s
respectivamente, todo `CACHED`, con `docker compose build` sobre ambos servicios).

Lo que sí invalida ese cache y fuerza a repetir el build largo:

- Cambiar `pom.xml`, `mvnw`, `.mvn/` o `src/` — invalida la etapa de dependencias/compilación de `api`.
- Cambiar `Dockerfile.bonsai` o `entrypoint-bonsai.sh` — invalida la compilación del fork CUDA
  (~20 min, ver [ADR-0009](docs/adrs/0009-bonsai-8b-integracion-pospuesta.md)).
- Correr `docker builder prune`, o que Docker Desktop libere espacio solo por presión de disco — el
  cache de build es finito y compite con el de otros proyectos en la misma máquina.

Nada de esto lo dispara un `down`/`up-bonsai` normal.

## Dónde vive cada cosa

Todo lo pesado y persistente cae bajo `KB_DATA_DIR` (por defecto `./.data`, junto al repo):
modelos de Ollama, datos de PostgreSQL y el ONNX del reranker. Esto mantiene libre la unidad del
sistema sin depender de configuración del host.

El contenido a ingerir vive en `KB_VAULT_DIR` (por defecto `../../vault`, **fuera** del repo), con dos
subcarpetas fijas: `vault/documentos` (Markdown, texto, PDF, DOCX, PPTX) y `vault/repos` (repos Git
locales a indexar). El panel de administración (`/admin.html`) muestra el estado de cada archivo en
tiempo real — ver [ADR-0011](docs/adrs/0011-vault-unificado.md).

Si tu unidad `C:` va justa, además conviene mover el *data-root* de Docker Desktop a otra unidad
desde **Settings → Resources → Advanced → Disk image location**. Es configuración del host, fuera
de este repo, y por eso queda como recomendación y no como requisito.

### El único hueco del "infraestructura como código"

`docker-compose` no puede fijar la memoria de la VM de WSL2. Eso vive en
`%USERPROFILE%\.wslconfig`. Versionamos la plantilla en [`wslconfig.example`](wslconfig.example),
pero aplicarla es un paso manual:

```powershell
Copy-Item wslconfig.example $env:USERPROFILE\.wslconfig
wsl --shutdown
```

## Stack

| Pieza | Elección |
|---|---|
| Núcleo | Java 21 · Spring Boot 4.1 · Spring Modulith 2.1 |
| Datos | PostgreSQL 18 · pgvector 0.8.6 · Flyway |
| Generación | API compatible con OpenAI — planner, verificador de grounding y síntesis; `llama-server` (Bonsai) u Ollama (Ministral, `gemma3:4b`) según el perfil, ver [Perfiles de modelo](#perfiles-de-modelo) |
| Destilación (Teams, F6) | Ollama con `gemma3:4b` |
| Embeddings | `bge-m3` por Ollama, 1024 dimensiones, multilingüe |
| Reranking | `bge-reranker-v2-m3` sobre ONNX Runtime, en proceso |
| Extracción de documentos | Docling (`docling-serve`) — PDF, DOCX y PPTX a Markdown, ver [ADR-0010](docs/adrs/0010-docling-reemplaza-pdfbox.md) |
| Adaptadores | HTML/JS sin build · protocolo Bot Connector implementado directo |

Spring AI se usa **solo como cliente de Ollama**. Sus abstracciones de RAG y su `VectorStore`
quedan fuera a propósito: un `VectorStore` no sabe expresar cuatro señales independientes
fusionadas por RRF, que es justamente el aporte del diseño.

El bot de Teams implementa el protocolo Bot Connector directo porque **todos los SDK de Bot
Framework están retirados** — el de Java desde noviembre de 2023, el resto con soporte final en
diciembre de 2025. Azure AI Bot Service sigue ejecutando bots V4 sin fin de vida anunciado, así
que hablarle al protocolo es hoy la opción más duradera. Deshabilitado por defecto
(`KB_TEAMS_HABILITADO=false`): el producto se demuestra completo sin él. Para conectarlo a un bot
real, ver [`docs/teams/registro-azure-bot.md`](docs/teams/registro-azure-bot.md); contra el Bot
Framework Emulator alcanza con `KB_TEAMS_HABILITADO=true` y apuntarlo a
`http://localhost:8080/api/messages`, sin credenciales.

## Autenticación

`KB_API_TOKEN` vacío (el default) deja el API sin autenticación. Con un valor, `/api/ask`,
`/api/search` y `/api/ingest/*` exigen `Authorization: Bearer <token>`. Quedan afuera a propósito
`/api/chat` y `/api/preview` (la UI web no tiene login de persona en este MVP) y `/api/messages`
(el Bot Connector valida su propio JWT). Detalle y decisiones pendientes en
[`docs/architecture.md`](docs/architecture.md).

## Documentación

- [`docs/architecture.md`](docs/architecture.md): arquitectura completa, esquema de datos, límites
  entre módulos y equivalencias con el artículo de Cerebras.
- [`docs/adrs/`](docs/adrs): decisiones de diseño que no son obvias leyendo el código.
- [`docs/plans/plan-base-conocimiento.md`](docs/plans/plan-base-conocimiento.md): el plan de
  ejecución completo, fase por fase, con los hallazgos reales de cada una.

## Desarrollo

```bash
./mvnw test      # incluye los gates de arquitectura
./mvnw verify    # build completo
```

Los gates viven en `ArquitecturaTest` desde el primer commit: una frontera que solo existe en un
documento se erosiona en la tercera semana.

## Licencia

MIT — ver [LICENSE](LICENSE).
