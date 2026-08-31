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
- **Opcional**: GPU NVIDIA. En Windows con Docker Desktop no hace falta instalar
  `nvidia-container-toolkit`: la tarjeta se pasa a WSL2 sola. En Linux sí

Java y Maven **no** hacen falta para ejecutarlo: el build ocurre dentro de Docker. Solo los
necesitas para desarrollar (Java 25 y el wrapper `./mvnw` incluido).

### Instalación desde cero (Windows)

Estas son todas las herramientas, en orden. Solo las dos primeras son obligatorias para
**ejecutar** el sistema; el resto son para desarrollarlo o para comodidad.

| # | Herramienta | Para qué | Instalar | Verificar |
|---|---|---|---|---|
| 1 | **Docker Desktop** (con WSL2) | Todo corre en contenedores, incluido el build de la app | `winget install Docker.DockerDesktop` — luego ábrelo una vez y deja que termine de configurar WSL2 | `docker version` y `docker compose version` |
| 2 | **Git for Windows** | Clonar el repo, y su `sh.exe` es el shell que usa `make` (ver abajo) | `winget install Git.Git` | `git --version` |
| 3 | **GNU Make** | Los comandos de este README. Si no lo quieres, mira [Sin `make`](#sin-make) | `winget install ezwinports.make` | `make --version` |
| 4 | **Driver NVIDIA** *(opcional)* | GPU para el LLM y los embeddings. Docker Desktop pasa la tarjeta a WSL2 solo, **no** hace falta instalar `nvidia-container-toolkit` a mano en Windows | [nvidia.com/drivers](https://www.nvidia.com/download/index.aspx) — para el perfil Bonsai hace falta **≥ 560** | `nvidia-smi` |
| 5 | **JDK 25** *(solo para desarrollar)* | Compilar y correr las pruebas fuera de Docker | `winget install EclipseAdoptium.Temurin.25.JDK` y apunta `JAVA_HOME` ahí | `make jdk-check` |
| 6 | **gitleaks** *(solo para desarrollar)* | El gate de secretos (`make secrets`) | `winget install Gitleaks.Gitleaks` | `gitleaks version` |
| 7 | **Node 20+** *(opcional)* | Solo para la evaluación de 100 preguntas (`eval-100-preguntas/`) | `winget install OpenJS.NodeJS.LTS` | `node --version` |

Después de instalar, **cierra y vuelve a abrir la terminal** para que tome el `PATH` nuevo, clona el
repo y sigue con [Arranque](#arranque).

En Linux o macOS es lo mismo sin los pasos 2 y 3 (`make` y `sh` ya están), y con
`nvidia-container-toolkit` instalado a mano si quieres GPU.

### `make` en Windows

Funciona igual desde **PowerShell** y desde **Git Bash**. Lo único que hace falta es
[Git for Windows](https://git-scm.com/download/win) instalado: el `Makefile` busca su `sh.exe` y lo
usa como shell, porque las recetas son POSIX (pipes, `if [ ... ]`, `command -v`).

Sin eso, Make caería a `cmd.exe` y casi todo fallaría con «no se reconoce como un comando interno o
externo» — no hace falta abrir Git Bash a propósito, ni agregar nada al `PATH` a mano.

Para compilar (`make build`, `test`, `verify`, `check`) además necesitas **JDK 25** en `JAVA_HOME`.
`make jdk-check` lo verifica y te dice exactamente qué poner si no cuadra, en vez de dejar que
Maven falle con `release version 25 not supported` recién después de resolver las dependencias.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25...'   # PowerShell, para la sesión
```



### Sin `make`

`make` no hace magia: encadena archivos de compose y arma unos `curl`. Si no lo puedes instalar,
esto es cada comando en crudo. La diferencia real es que **el reparto de la GPU deja de ser
automático**: `make` mira la tarjeta con `nvidia-smi` y elige los `-f` y el modelo de embeddings por
ti; sin él, esa decisión la tomas tú.

Primero elige tu línea base de compose y reutilízala en todos los comandos:

```bash
# sin GPU
docker compose ...

# con GPU (LLM en la tarjeta)
docker compose -f compose.yml -f compose.gpu.yml ...

# con GPU y suficiente VRAM (≥ 8 GB) para que docling también la use
docker compose -f compose.yml -f compose.gpu.yml -f compose.docling-gpu.yml ...
```

| En vez de | Corre |
|---|---|
| `make up` | `docker compose <tus -f> up -d --build` |
| `make down` | `docker compose <tus -f> down --remove-orphans` |
| `make restart` | `docker compose <tus -f> up -d --build api` |
| `make logs` | `docker compose <tus -f> logs -f api` |
| `make ps` | `docker compose <tus -f> ps` |
| `make psql` | `docker compose <tus -f> exec db psql -U kb -d baseconocimiento` |
| `make health` | `curl -fsS http://localhost:8080/actuator/health` |
| `make ingest` / `seed` | `curl -fsS -X POST http://localhost:8080/api/ingest/local-docs` |
| `make ingest-repos` | `curl -fsS -X POST http://localhost:8080/api/ingest/repos-locales` |
| `make pull-models` | `docker compose <tus -f> exec ollama ollama pull gemma3:4b` y `... ollama pull bge-m3`, más el reranker (abajo) |
| `make pin-embeddings-cpu` | `docker compose <tus -f> exec -T ollama sh -c 'printf "FROM bge-m3\nPARAMETER num_gpu 0\n" > /tmp/Modelfile.cpu && ollama create bge-m3-cpu -f /tmp/Modelfile.cpu'` |
| `make build` | `./mvnw -B clean package -DskipTests` |
| `make test` | `./mvnw -B test` |
| `make verify` | `./mvnw -B clean verify` |
| `make lint` | `./mvnw -q spotless:check checkstyle:check` |
| `make format` | `./mvnw -q spotless:apply` |
| `make secrets` | `gitleaks detect --no-banner --redact` |

El reranker (`make pull-reranker`) baja dos archivos y **verifica su SHA-256** — hazlo igual, la
verificación es parte del punto:

```bash
mkdir -p .data/models/reranker
curl -fL -o .data/models/reranker/model.onnx \
  https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/resolve/main/onnx/model_int8.onnx
curl -fL -o .data/models/reranker/tokenizer.json \
  https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/resolve/main/tokenizer.json
sha256sum .data/models/reranker/model.onnx      # 912fc1215c2dbff6499700534bd8d31253af01573861abbfc43afd1fab6cce5d
sha256sum .data/models/reranker/tokenizer.json  # 8bf8afbfd11306bd872018c53bfdf2e160a56f8edbcf49933324404791c148d3
```

Sin `make` también tienes que elegir a mano el modelo de embeddings en tu `.env`:
`KB_EMBEDDINGS_MODELO=bge-m3` si la tarjeta tiene ≥ 6 GB, `bge-m3-cpu` si tiene menos (y en ese caso
crea el modelo con el comando de `pin-embeddings-cpu` de la tabla). El porqué está en
[Reparto de la GPU](#reparto-de-la-gpu).

## Arranque

```bash
cp .env.example .env
make gpu-check          # qué tarjeta ve y cómo va a repartirla
make up                 # levanta db, ollama, docling-serve y api
make pull-models        # ~5.5 GB, una sola vez
make health             # confirma que no falta ningún modelo
make seed               # ingiere el corpus de ejemplo
```

Abre <http://localhost:8080>.

## Reparto de la GPU

`make` mira la tarjeta con `nvidia-smi` —VRAM, Compute Capability y versión del driver— y decide
solo qué corre en ella. **No hay nada que configurar**: el mismo `make up` hace lo correcto en una
T600 de 4 GB y en una RTX 3060 de 6 GB.

```bash
make gpu-check    # qué tarjeta vio, qué le dio a la GPU y por qué
make up           # levanta con ese reparto, y lo imprime al terminar
```

| Etapa | Cuándo va a la GPU |
|---|---|
| **LLM** (planner, verificador de grounding, síntesis) | Siempre que haya tarjeta |
| **Embeddings** (ingesta *y* consultas) | Desde 6 GB de VRAM (`KB_VRAM_EMBEDDINGS_GPU`) |
| **Extracción de PDF/DOCX** (docling) | Desde 8 GB de VRAM (`KB_VRAM_DOCLING_GPU`) |
| **Reranker** (cross-encoder) | Nunca — ver abajo |

**Por qué los embeddings no siempre van a la GPU.** No es prudencia: está medido. En una T600 de
4 GB, `gemma3:4b` **no entra completo ni estando solo** — Ollama offloadea capas hasta que caben y
queda en 40% GPU / 60% CPU. Ahí, darle VRAM a `bge-m3` solo empeora al LLM, y moverlo a CPU (donde
el AVX-512 con VNNI lo hace barato) resultó una mejora neta, sin costo de calidad. Con 6 GB o más
entran los dos y los embeddings se quedan en la tarjeta, que es lo que conviene para la ingesta. El
detalle está en [`docs/investigacion-vram-y-modelo-llm.md`](docs/investigacion-vram-y-modelo-llm.md).

**Por qué docling tiene un umbral más alto.** `docling-serve` no libera la VRAM entre conversiones
(bug conocido y sin fix, ver [ADR-0010](docs/adrs/0010-docling-reemplaza-pdfbox.md)), así que solo
se le da la tarjeta cuando sobra margen. Si te quedas sin VRAM,
`docker compose restart docling-serve` la libera.

**Por qué el reranker se queda en CPU.** El proyecto usa la build **CPU** de ONNX Runtime
(`com.microsoft.onnxruntime`). Ponerlo en GPU no es una variable de entorno: exige cambiar la
dependencia por `onnxruntime_gpu` y meter CUDA en la imagen de la app. Ninguna opción de este README
lo cambia — mejor decirlo que insinuar lo contrario.

Todo el reparto se puede forzar, sin tocar código:

```bash
KB_GPU=1 make up                  # fuerza el perfil GPU (o KB_GPU=0 para apagarlo)
KB_DOCLING_GPU=1 make up          # docling a la GPU sin esperar al umbral
KB_VRAM_EMBEDDINGS_GPU=5120 make up   # mueve el umbral si mediste otra cosa
```

Y si fijas `KB_EMBEDDINGS_MODELO` en tu `.env`, manda eso y la detección deja de aplicar —
`make gpu-check` te lo avisa.

### Si dice «Perfil activo: CPU» y tu equipo sí tiene GPU

Corre `make gpu-check`: muestra qué shell usó Make, si encontró `nvidia-smi`, qué tarjetas y qué
driver hay. Si la tarjeta aparece ahí pero el perfil dice CPU, falló la detección y no el hardware:

```bash
KB_GPU=1 make up
```

### Perfil Bonsai y la versión de CUDA

`make up-bonsai` compila llama.cpp con CUDA. Las dos cosas que dependen de tu equipo también salen
de `nvidia-smi`, así que normalmente no tienes que tocarlas:

| Variable | Qué es | De dónde sale |
|---|---|---|
| `BONSAI_CUDA_ARCH` | Compute Capability: 75 Turing (T600, RTX 20xx), 86 Ampere (RTX 30xx), 89 Ada (RTX 40xx), 120 Blackwell (RTX 50xx) | `nvidia-smi --query-gpu=compute_cap` |
| `BONSAI_CUDA_TAG` | Imagen base `nvidia/cuda`. Cada versión exige un driver mínimo y lo verifica al arrancar: 12.6.0 pide ≥ 560, 12.4.1 ≥ 550, 12.2.2 ≥ 535 | La versión del driver instalado |

`up-bonsai` imprime las dos **antes** de compilar, junto con el driver mínimo. Si aun así falla con
`unsatisfied condition: cuda>=12.6`, el driver es más viejo de lo que dice `nvidia-smi`: actualízalo,
o fija `BONSAI_CUDA_TAG=12.4.1` en tu `.env`.

`make gpu-up` fuerza el perfil GPU para un arranque suelto. `make help` lista todo lo demás.


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
| Núcleo | Java 25 · Spring Boot 4.1 · Spring Modulith 2.1 |
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
make check       # lint + build + test -- la señal de confianza local antes de un commit
make hooks       # instala los git hooks de Lefthook (correr una vez, desde la raíz del monorepo)
```

Los gates viven en `ArquitecturaTest` desde el primer commit: una frontera que solo existe en un
documento se erosiona en la tercera semana. Detalle de los 8 controles y los 7 hooks de agente
instalados por `instrumentacion-java-ia` en `AGENTS.md` y en
`validacion-workshop/f2-preparar-proyecto.md` (raíz del monorepo).

**Prerrequisito en Windows**: Git Bash (viene con Git for Windows). Los hooks de agente y de
Lefthook son scripts bash; sin Git Bash instalado, Claude Code cae a PowerShell y esos scripts
quedan inertes en silencio.

## Agente de IA (Claude Code)

Este proyecto trae instrumentación para agentes de código con IA (`.claude/settings.json`,
`.mcp.json`, ambos en la raíz del monorepo — no aquí). Para usar los servidores MCP:

```bash
export GITHUB_PAT=...      # personal access token de GitHub, para el servidor MCP de GitHub
export APP_DSN=postgres://kb:kb@localhost:5432/baseconocimiento?sslmode=disable  # servidor MCP DBHub
```

`.mcp.json` queda en `⏸ Pending approval` hasta que confíes el workspace: corre `claude` en la raíz
del monorepo, acepta el diálogo de confianza, y usa `/mcp` para confirmar que cada servidor conectó.

El hook de auditoría (si está activo) guarda el `tool_input` completo de cada llamada a
herramienta en `logs/audit.log` (raíz del monorepo, gitignored) — puede contener cualquier dato
que haya pasado por una herramienta durante la sesión.

## Licencia

MIT — ver [LICENSE](LICENSE).
