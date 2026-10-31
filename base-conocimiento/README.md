# Base de Conocimiento interna

Un RAG interno que responde preguntas sobre tus documentos, tu código, tus canales de Teams y tus
work items, con **citas verificables**. Reproduce la arquitectura que Cerebras describió en
*How we built our knowledge base*, con dos diferencias:

- En vez de Slack, **dos adaptadores**: una UI HTML/JS y un bot de Teams.
- **Costo cero**: modelos abiertos que corren en tu máquina. Sin APIs de pago, sin nube.

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
  propone hasta tres reescrituras con estrategias distintas (término formal de la fuente,
  descomposición en el concepto más concreto, sinónimos oficiales), viendo como pistas los
  fragmentos que la primera búsqueda ya encontró, para usar el vocabulario real de la fuente en
  vez de adivinarlo. En la UI web, si hay dos o
  más, la persona elige con cuál buscar, la edita a mano si ninguna sirve, o se queda con su
  pregunta tal cual, y decide si quiere la respuesta en el idioma original de las fuentes en vez
  de español; con una sola se responde de inmediato. En Teams y `/api/ask` se aplica la primera sola y se repite el mismo plan de
  herramientas.
- **Sabe cuándo no sabe**: si tras eso sigue sin evidencia suficiente, corta con un mensaje fijo sin
  gastar la llamada de síntesis.

Es Agentic RAG **acotado**, no un agente abierto tipo ReAct: el conjunto de acciones es fijo (6
herramientas + 1 reformulación + 1 verificación) y el reintento es de una sola vuelta, no una
planificación libre sin límite.

## Acciones sobre los documentos que eliges

Aparte de preguntar, la página de chat deja elegir documentos en la barra lateral y hacer cosas
con ellos que no son una pregunta: **resumir** (un resumen por documento), **sintetizar** (un solo
texto que cruza los documentos), **lluvia de preguntas** (por niveles de Bloom, con la plantilla
5W1H), **lluvia de ideas** y **traducir** (el
documento completo, a cualquier idioma, con descarga en Markdown). El mismo traductor sirve dentro
del chat: un modo traducir en la barra de entrada y «Traducir» sobre cualquier turno. Cada
resultado es un turno más de la conversación y muestra cómo entró cada documento al modelo — un
documento largo no se recorta: se lee entero por pasadas (notas por tramo, condensadas hasta que
caben) y la cobertura dice cuántas fueron, con progreso mientras avanza. Si leerlo entero tardaría
más de 10 minutos, antes de empezar se ofrece elegir: completo, por capítulos o rápido, cada uno con
su tiempo estimado ([ADR-0016](docs/adrs/0016-acciones-eligen-modo-de-lectura.md)).

Todo eso vive en un módulo **independiente del RAG**: comparte solo el vault indexado y el cliente
del LLM. Detalle en
[`docs/architecture.md`](docs/architecture.md#acciones-sobre-documentos-seleccionados-apiacciones)
y [ADR-0013](docs/adrs/0013-modulo-acciones-independiente-del-rag.md).

## Requisitos

- Docker Desktop con WSL2, para la base, `docling-serve` y la app
- Node 18 o más nuevo: `make` lo usa para manejar `llama-server`
- ~10 GB libres en disco (3,4 GB de modelos y llama.cpp, más la base y las imágenes)
- 16 GB de RAM (recomendado 32)
- **Opcional**: GPU NVIDIA con driver ≥ 551. El LLM y los embeddings los sirve `llama-server`
  directo en Windows, fuera de Docker ([ADR-0017](docs/adrs/0017-llama-cpp-nativo-reemplaza-a-ollama.md)),
  así que la tarjeta no tiene que pasar por WSL2

Java y Maven **no** hacen falta para ejecutarlo: el build ocurre dentro de Docker. Solo los
necesitas para desarrollar (Java 25 y el wrapper `./mvnw` incluido).

### Instalación desde cero (Windows)

Estas son todas las herramientas, en orden. Las cuatro primeras hacen falta para **ejecutar** el
sistema con `make`; el resto son para desarrollarlo o para comodidad.

| # | Herramienta | Para qué | Instalar | Verificar |
|---|---|---|---|---|
| 1 | **Docker Desktop** (con WSL2) | Todo corre en contenedores, incluido el build de la app | `winget install Docker.DockerDesktop` — luego ábrelo una vez y deja que termine de configurar WSL2 | `docker version` y `docker compose version` |
| 2 | **Git for Windows** | Clonar el repo, y su `sh.exe` es el shell que usa `make` (ver abajo) | `winget install Git.Git` | `git --version` |
| 3 | **GNU Make** | Los comandos de este README. Si no lo quieres, mira [Sin `make`](#sin-make) | `powershell -ExecutionPolicy Bypass -File scripts\instalar-make.ps1` (ver [abajo](#instalar-make-con-el-script)), o a mano `winget install ezwinports.make` | `make --version` |
| 4 | **Node 18+** | `make` arranca y detiene `llama-server` con `scripts/llama-cpp.mjs`, y corre las pruebas del JavaScript de la UI | `winget install OpenJS.NodeJS.LTS` | `node --version` |
| 5 | **Driver NVIDIA** *(opcional)* | GPU para el LLM. `llama-server` corre en Windows con el build CUDA 12.4 de llama.cpp: no hace falta instalar CUDA ni `nvidia-container-toolkit` | [nvidia.com/drivers](https://www.nvidia.com/download/index.aspx) — **≥ 551** (el mínimo de CUDA 12.4) | `nvidia-smi` |
| 6 | **JDK 25** *(solo para desarrollar)* | Compilar y correr las pruebas fuera de Docker | `winget install EclipseAdoptium.Temurin.25.JDK` y apunta `JAVA_HOME` ahí | `make jdk-check` |
| 7 | **gitleaks** *(solo para desarrollar)* | El gate de secretos (`make secrets`) | `winget install Gitleaks.Gitleaks` | `gitleaks version` |

Después de instalar, **cierra y vuelve a abrir la terminal** para que tome el `PATH` nuevo, clona el
repo y sigue con [Arranque](#arranque).

En Linux o macOS es lo mismo sin los pasos 2 y 3 (`make` y `sh` ya están). La diferencia es
`llama-server`: `make llm-instalar` solo baja el build de Windows, así que ahí lo instalas tú (el
paquete de tu sistema, Homebrew, o compilado con CUDA) y queda en el `PATH`, o apuntas
`LLAMA_SERVER` a su ruta. En Linux, además, arráncalo con `KB_LLAMA_HOST=0.0.0.0`: un servidor en
`127.0.0.1` no se ve desde los contenedores.

### `make` en Windows

Funciona igual desde **PowerShell** y desde **Git Bash**. Lo único que hace falta es
[Git for Windows](https://git-scm.com/download/win) instalado: el `Makefile` busca su `sh.exe` y lo
usa como shell, porque las recetas son POSIX (pipes, `if [ ... ]`, `command -v`).

Sin eso, Make caería a `cmd.exe` y casi todo fallaría con «no se reconoce como un comando interno o
externo» — no hace falta abrir Git Bash a propósito, ni agregar nada al `PATH` a mano.

#### Instalar `make` con el script

Desde la raíz del repo, en PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\instalar-make.ps1
```

Si `make` ya está, muestra la versión y no instala nada. Si no, lo instala con winget
(`ezwinports.make`, GNU Make 4.x; no `GnuWin32.Make`, que es la 3.81 de 2006) y, si winget no lo
dejó en el `PATH`, agrega su carpeta al `PATH` de tu usuario, sin pedir administrador. También avisa
si falta Git for Windows. Se puede correr las veces que quieras. `-ExecutionPolicy Bypass` vale
solo para esa ejecución: no cambia la política de tu equipo. Al terminar, cierra y vuelve a abrir
las terminales que ya tenías abiertas.

Para compilar (`make build`, `test`, `verify`, `check`) además necesitas **JDK 25** en `JAVA_HOME`.
`make jdk-check` lo verifica y te dice exactamente qué poner si no cuadra, en vez de dejar que
Maven falle con `release version 25 not supported` recién después de resolver las dependencias.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25...'   # PowerShell, para la sesión
```



### Sin `make`

`make` no hace magia: arranca `llama-server` con un script de node, encadena archivos de compose y
arma unos `curl`. Si no lo puedes instalar, esto es cada comando en crudo. La diferencia real es
que **el reparto de la GPU deja de ser automático**: `make` mira la tarjeta con `nvidia-smi` y
decide cuántas capas van a la GPU en cada instancia; sin él, lo decides tú con las variables de
abajo.

`llama-server` lee su configuración del entorno. En PowerShell, con GPU de 4 GB (el LLM a la
tarjeta, los embeddings a la CPU):

```powershell
$env:KB_LLM_GPU_CAPAS = '99'; $env:KB_EMBEDDINGS_GPU_CAPAS = '0'
node scripts/llama-cpp.mjs instalar     # una sola vez
node scripts/llama-cpp.mjs arrancar
```

Sin GPU, `KB_LLM_GPU_CAPAS=0` y `KB_LLAMA_CPP_VARIANTE=cpu` (antes de instalar). Con 8 GB de VRAM o
más, agrega `-f compose.yml -f compose.docling-gpu.yml` a los comandos de compose.

| En vez de | Corre |
|---|---|
| `make up` | `node scripts/llama-cpp.mjs arrancar` y después `docker compose up -d --build --remove-orphans` |
| `make down` | `docker compose down --remove-orphans` y `node scripts/llama-cpp.mjs detener` |
| `make llm-estado` | `node scripts/llama-cpp.mjs estado` |
| `make restart` | `docker compose up -d --build api` |
| `make logs` | `docker compose logs -f api` |
| `make ps` | `docker compose ps` |
| `make psql` | `docker compose exec db psql -U kb -d baseconocimiento` |
| `make health` | `curl -fsS http://localhost:8080/actuator/health` |
| `make ingest` / `seed` | `curl -fsS -X POST http://localhost:8080/api/ingest/local-docs` |
| `make ingest-repos` | `curl -fsS -X POST http://localhost:8080/api/ingest/repos-locales` |
| `make pull-models` | `node scripts/llama-cpp.mjs instalar`, los dos GGUF y el reranker (abajo) |
| `make build` | `./mvnw -B clean package -DskipTests` |
| `make test` | `./mvnw -B test` |
| `make verify` | `./mvnw -B clean verify` |
| `make lint` | `./mvnw -q spotless:check checkstyle:check` |
| `make format` | `./mvnw -q spotless:apply` |
| `make secrets` | `gitleaks detect --no-banner --redact` |

Los modelos (`make pull-gguf` y `make pull-reranker`) son archivos que se bajan y **se verifican
por SHA-256** — hazlo igual, la verificación es parte del punto:

```bash
mkdir -p .data/models/llm .data/models/embeddings .data/models/reranker
curl -fL -o .data/models/llm/granite-4.1-3b-Q3_K_M.gguf \
  https://huggingface.co/ibm-granite/granite-4.1-3b-GGUF/resolve/main/granite-4.1-3b-Q3_K_M.gguf
curl -fL -o .data/models/embeddings/bge-m3-FP16.gguf \
  https://huggingface.co/gpustack/bge-m3-GGUF/resolve/main/bge-m3-FP16.gguf
curl -fL -o .data/models/reranker/model.onnx \
  https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/resolve/main/onnx/model_int8.onnx
curl -fL -o .data/models/reranker/tokenizer.json \
  https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/resolve/main/tokenizer.json
sha256sum .data/models/llm/granite-4.1-3b-Q3_K_M.gguf  # 43db9c8da5360eaeef0039cdc28aefdf43ede98ddf36ec7edfd6d4ee89f36403
sha256sum .data/models/embeddings/bge-m3-FP16.gguf     # daec91ffb5dd0c27411bd71f29932917c49cf529a641d0168496c3a501e3062c
sha256sum .data/models/reranker/model.onnx             # 912fc1215c2dbff6499700534bd8d31253af01573861abbfc43afd1fab6cce5d
sha256sum .data/models/reranker/tokenizer.json         # 8bf8afbfd11306bd872018c53bfdf2e160a56f8edbcf49933324404791c148d3
```

## Arranque

```bash
cp .env.example .env
make gpu-check          # qué tarjeta ve y cómo va a repartirla
make pull-models        # llama.cpp y los modelos, ~3.4 GB, una sola vez
make up                 # levanta llama-server (LLM y embeddings) y db, docling-serve y api
make health             # confirma que la api ve las dos instancias de llama-server
make seed               # copia corpus/ al vault y lo ingiere
```

Abre <http://localhost:8080>.

`llama-server` corre en tu máquina, **fuera de Docker**: dos procesos (el LLM en el puerto 8081 y
los embeddings en el 8082) que `make up` arranca y `make down` detiene. Si reinicias el equipo,
Docker Desktop levanta solo los contenedores pero no a `llama-server`: vuelve a correr `make up`
(o `make llm-up`). `make llm-estado` dice si cada instancia corre y dónde está su log.

La primera vez después de instalar, el LLM tarda más de un minuto en cargar y la primera respuesta
otro par de minutos (los kernels de CUDA se compilan para tu tarjeta y quedan en caché). Desde
la segunda vez carga en segundos.

`make seed` hace dos cosas, y la primera es fácil de pasar por alto: el corpus de ejemplo viene
versionado en `corpus/`, pero el vault vive **fuera del repo** (`KB_VAULT_DIR`, por defecto
`../../vault`). `seed` llama antes a `make vault-init`, que crea `vault/documentos` y `vault/repos`
y copia el corpus ahí sin pisar nada que ya exista.

Si te saltas ese paso el sistema **levanta perfecto y responde «No encontré información
suficientemente relevante» a todo**, sin un solo error en los logs: el *bind mount* crea el vault
vacío en vez de fallar, así que la ingesta corre sobre cero documentos y esa respuesta es correcta.
Para ver qué hay realmente ingerido, `scripts/diagnostico-ingesta.sql` o el panel
<http://localhost:8080/admin.html>.


### Si una consulta falla: `llama-server` caído o sin VRAM

Desde la interfaz se ve igual —la respuesta se corta, o dice «El LLM no responde»— pero la causa
casi siempre es una de estas. Para saber cuál:

```bash
make llm-estado         # cada instancia: si corre, si responde, y dónde está su log
make capturar-error     # deja scripts/error-api.txt con la excepción y el log de llama-server
```

| Lo que se ve | Qué pasa | Cómo se arregla |
|---|---|---|
| `llm-estado`: `sin proceso` / `no responde` | `llama-server` no está arriba: reiniciaste el equipo, o levantaste con `docker compose up` a mano | `make llm-up` |
| `llm-estado`: `cargando el modelo` | Recién arrancó y todavía carga el GGUF (la primera vez tarda más de un minuto) | Esperar |
| `make up` falla con el log de `llm` y `out of memory` | No entra en la VRAM: otro proceso la ocupa (docling en GPU, otro LLM) | Liberar la tarjeta, o `KB_GPU=0 make up` para correr en CPU |
| `make up` dice que falta un modelo | El GGUF no está en `KB_DATA_DIR/models` | `make pull-models` |
| `make up` dice que el puerto ya lo usa otro proceso | Algo más escucha en 8081 u 8082 | Detenerlo, o cambiar `KB_LLM_PORT` / `KB_EMBEDDINGS_PORT` en tu `.env` |

Un GGUF dañado no es un caso aparte: `make pull-gguf` verifica el SHA-256 al bajarlo y no deja un
archivo a medias con el nombre final. Si sospechas de uno que ya estaba, bórralo y vuelve a correr
`make pull-gguf`.

### Si `make up` falla con «offline mode»

El primer `make up` compila la aplicación dentro de Docker, y eso tarda: unos **11 minutos** solo
en bajar el árbol de dependencias de Maven. No está colgado.

Ese trabajo se guarda en un *cache mount* de BuildKit (`id=maven-repo`), no en la imagen. Y ahí está
la trampa, porque el **cache de capas** y el **cache mount** tienen vidas separadas:

- La capa que corre `dependency:go-offline` se marca `CACHED` y sobrevive.
- El contenido del mount —los `.jar` de verdad— lo puede vaciar el recolector de basura de
  BuildKit cuando necesita espacio, sin avisar.

Cuando coinciden esas dos cosas, `go-offline` **no se vuelve a ejecutar** (su capa está cacheada) y
la etapa de compilación arranca en modo offline sin un solo artefacto:

```
#14 [deps 6/6] RUN ... dependency:go-offline
#14 CACHED                                      <- no se ejecutó

#18 [build 2/2] RUN ... ./mvnw -o -B -q clean package -DskipTests
[ERROR] Cannot access central (https://repo.maven.apache.org/maven2) in offline mode
        y el artefacto ... has not been downloaded from it before
```

El nombre del artefacto varía según cuál pida Maven primero, así que **parece un problema de
dependencias del proyecto y no lo es**. La solución:

```bash
make cache-reciclar     # invalida el cache de build; vuelve a tardar ~11 min
make up
```

`make up` detecta esa firma en el log y te lo dice solo cuando ocurre, con el comando ya
escrito. Ojo con lo que cuesta: `cache-reciclar` corre `docker builder prune -f`, que se lleva el
cache de build de **todos** los proyectos de la máquina — BuildKit no permite podar un mount suelto
por su id. No toca imágenes, contenedores ni volúmenes de datos.

**Cuándo aparece:** casi siempre tras un `git pull`. Con el mount ya desalojado, el build sigue
funcionando mientras nada invalide la capa de compilación; el fallo asoma en cuanto cambia `src/`,
que es justo lo que hace actualizar el repo.

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

**Por qué los embeddings no siempre van a la GPU.** Con `gemma3:4b`, el LLM anterior, estaba
medido: en una T600 de 4 GB **no entraba completo ni estando solo** (40% GPU / 60% CPU), darle
VRAM a `bge-m3` solo empeoraba al LLM, y moverlo a CPU (donde el AVX-512 con VNNI lo hace barato)
resultó una mejora neta, sin costo de calidad. Granite 4.1 3B Q3_K_M sí entra (2.4 GB con
`llama-server`), pero los dos juntos no se midieron en 4 GB, así que el umbral sigue en 6 GB: por
debajo, los embeddings van a CPU y `llama-server` ni abre un contexto en la tarjeta. El detalle
está en [`docs/investigacion-vram-y-modelo-llm.md`](docs/investigacion-vram-y-modelo-llm.md).

**Por qué docling tiene un umbral más alto.** Ya no es prudencia: está medido (sesión 27 de
[`docs/investigacion-vram-y-modelo-llm.md`](docs/investigacion-vram-y-modelo-llm.md)). Sumando las
tres etapas en la tarjeta a la vez:

| Componente | VRAM |
|---|---|
| `gemma3:4b` a contexto 4096 | 4.0 GB |
| `bge-m3` en GPU | ~1.2 GB |
| docling, pico con un documento grande | 2.2 GB |
| **Total** | **~7.4 GB** |

Por eso 8 GB: en 6 GB los tres no entran. Una RTX 3060 puede tener LLM y embeddings en la tarjeta
(5.2 GB), pero no docling encima. Durante la ingesta el LLM está ocioso, pero sigue residente
(`llama-server` lo carga al arrancar y no lo suelta), así que la suma es real y no un peor caso
teórico. Las cifras son de `gemma3:4b`; con Granite el LLM pesa menos, y el umbral no se remidió.

**La fuga de VRAM, y qué se puede hacer.** `docling-serve` no libera la VRAM entre conversiones
([#233](https://github.com/docling-project/docling-serve/issues/233), abierta desde junio de 2025
sin PR ni asignar), y no existe variable nativa para acotarla
([#440](https://github.com/docling-project/docling-serve/issues/440) la pide y sigue abierta). Lo
que se midió:

- **No es ilimitada por conversión.** Cinco conversiones seguidas del mismo documento dejaron la
  VRAM clavada en 799 MiB. Lo que se retiene es el pico del documento más pesado, no un delta por
  llamada — 2053 MiB en reposo tras procesar un PDF de 800 páginas, contra 629 al arrancar.
- **`GET /v1/clear/converters` no sirve**: existe, responde 200 y no libera un solo MiB. El caching
  allocator de PyTorch no devuelve la memoria al driver.
- **Reiniciar el proceso es la única vía**, y `compose.docling-gpu.yml` acota la huella base con
  `NUM_WORKERS=1`, `SHARE_MODELS=true` y `OPTIONS_CACHE_SIZE=1`: medido A/B con 4 conversiones
  concurrentes del mismo PDF, **2019 MiB con los defaults contra 799 MiB con esto**.

```bash
make docling-reciclar    # reinicia docling-serve e imprime la VRAM antes y después
```

Y ten presente que **docling solo interviene en la ingesta, nunca en las consultas**: no tiene por
qué estar residente compitiendo con el LLM si no estás ingiriendo documentos.

**Por qué el reranker se queda en CPU.** El proyecto usa la build **CPU** de ONNX Runtime
(`com.microsoft.onnxruntime`). Ponerlo en GPU no es una variable de entorno: exige cambiar la
dependencia por `onnxruntime_gpu` y meter CUDA en la imagen de la app. Ninguna opción de este README
lo cambia — mejor decirlo que insinuar lo contrario.

Todo el reparto se puede forzar, sin tocar código:

```bash
KB_GPU=1 make up                  # fuerza la GPU (o KB_GPU=0 para correr todo en CPU)
KB_DOCLING_GPU=1 make up          # docling a la GPU sin esperar al umbral
KB_VRAM_EMBEDDINGS_GPU=5120 make up   # mueve el umbral si mediste otra cosa
```

### Si dice que no hay GPU y tu equipo sí tiene una

Corre `make gpu-check`: muestra qué shell usó Make, si encontró `nvidia-smi`, qué tarjetas y qué
driver hay. Si la tarjeta aparece ahí pero dice que no hay GPU, falló la detección y no el hardware:

```bash
KB_GPU=1 make up
```

Para confirmar que el LLM de verdad está en la tarjeta, `nvidia-smi` tiene que listar un proceso
`llama-server.exe` con unos 2.4 GB de memoria en uso.

## Cambiar el modelo

El LLM que resuelve planner, verificador de grounding, síntesis, acciones y destilador de Teams se
habla por HTTP con la API compatible con OpenAI que expone `llama-server`. Hay **un solo modelo
por defecto**, Granite 4.1 3B Q3_K_M ([ADR-0015](docs/adrs/0015-granite-41-3b-q3km-como-llm-por-defecto.md)),
y ningún perfil: los diez `compose.<modelo>.yml` que había dependían de Ollama y salieron con él
([ADR-0017](docs/adrs/0017-llama-cpp-nativo-reemplaza-a-ollama.md)). Lo que se midió con cada uno
sigue en [la investigación](docs/investigacion-vram-y-modelo-llm.md).

Probar otro modelo es cambiar el GGUF que carga la instancia del LLM. En tu `.env`:

```bash
KB_LLM_GGUF=models/llm/otro-modelo.gguf   # relativo a KB_DATA_DIR
KB_LLM_MODELO=otro-modelo                 # la etiqueta con la que responde (--alias)
```

Baja el archivo a `KB_DATA_DIR/models/llm/` y reinicia: `make llm-down` y `make llm-up`. La api no
hace falta tocarla, salvo que el modelo pida otros parámetros (`KB_LLM_TIMEOUT`, el techo de
confianza): esos van en el `.env` y se aplican con `make restart`.

El contexto por consulta es `KB_LLM_CONTEXTO_TOKENS` (4096) y las consultas simultáneas,
`KB_LLM_PARALELO` (2). `llama-server` arranca con `-c` igual al producto de los dos, y la api lee el
mismo `KB_LLM_CONTEXTO_TOKENS` para saber cuánto texto entra en una pasada.

### Cuándo se reconstruye la imagen (y cuándo no)

**Nunca hace falta pedir el build a mano.** `make up` corre `docker compose ... up -d --build`: un
`git pull` seguido de `make up` nunca te deja corriendo código viejo sin aviso.

`--build` no significa «compila todo otra vez»: solo le pide a Docker que **revise** si algo cambió,
comparando el contexto de build con su cache de capas. Lo que pasa depende de qué cambió:

| Qué cambió desde el último build | Qué hace el `up` |
|---|---|
| Nada en el código | No compila. Termina en segundos, todo `CACHED`, y levanta la misma imagen (medido en vivo: 4.4 s para `api`). |
| `src/`, `pom.xml`, `mvnw` o `.mvn/` | Recompila `api` (Maven dentro de Docker) y levanta el binario nuevo. Es el caso de un `git pull` o un cambio de rama: basta con volver a correr `make up`. |
| Nada, pero corriste `docker builder prune` o Docker Desktop liberó espacio por presión de disco | Vuelve a hacer el build largo: el cache de build es finito y compite con el de otros proyectos de la misma máquina. |

Nada de esto lo dispara un `down`/`up` normal.

Tres cosas que se confunden con «construir» y no lo son:

- `make cache-reciclar` **no construye nada**: vacía el cache de BuildKit y te manda a correr el
  `up` de nuevo (ver «Si `make up` falla con «offline mode»»).
- `make -n <target>` **no ejecuta nada**: es un ensayo que solo imprime los comandos que correría.
  Sirve para ver qué `-f` y qué flags arma un target, no para levantar ni construir.
- `make restart` sí reconstruye, pero solo `api` (`up -d --build api`); no toca `db`,
  `docling-serve` ni `llama-server`.

Si aun así sospechas que estás corriendo una imagen vieja, compara la fecha de la imagen con tu
último commit y, si hace falta, fuerza un build desde cero:

```bash
docker image inspect base-conocimiento-api:latest --format '{{.Created}}'
git log -1 --format=%ci
docker compose build --no-cache api && make up
```

## Dónde vive cada cosa

Todo lo pesado y persistente cae bajo `KB_DATA_DIR` (por defecto `./.data`, junto al repo): los
GGUF del LLM y de los embeddings (`models/llm`, `models/embeddings`), el ONNX del reranker
(`models/reranker`), el build de llama.cpp con sus logs (`llama.cpp/`) y los datos de PostgreSQL.
Esto mantiene libre la unidad del sistema sin depender de configuración del host.

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
| Generación | Granite 4.1 3B Q3_K_M en `llama-server` (llama.cpp nativo, en el host), por la API compatible con OpenAI — planner, verificador de grounding, síntesis, acciones y destilador de Teams |
| Embeddings | `bge-m3` (F16) en una segunda instancia de `llama-server`, 1024 dimensiones, multilingüe |
| Reranking | `bge-reranker-v2-m3` sobre ONNX Runtime, en proceso |
| Extracción de documentos | Docling (`docling-serve`) — PDF, DOCX y PPTX a Markdown, ver [ADR-0010](docs/adrs/0010-docling-reemplaza-pdfbox.md) |
| Adaptadores | HTML/JS sin build · protocolo Bot Connector implementado directo |

Spring AI se usa **solo como cliente** (el de OpenAI, contra `llama-server`). Sus abstracciones de RAG y su `VectorStore`
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
`/api/chat`, `/api/preview` y las ocho rutas de `/api/acciones/*` (la UI web no tiene login de
persona en este MVP) y `/api/messages` (el Bot Connector valida su propio JWT). Detalle y decisiones pendientes en
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
