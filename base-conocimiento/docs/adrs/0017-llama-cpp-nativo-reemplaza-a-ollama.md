# ADR-0017: llama.cpp nativo sirve el LLM y los embeddings, sin Ollama

## Estado

Aceptado (2026-10-30). Cambia el «servido por Ollama» de ADR-0015; el modelo sigue siendo el mismo.

## Contexto

Desde el principio el LLM y los embeddings los servía Ollama dentro de Docker. En la T600 de 4 GB
del taller eso acumuló tres problemas que no tenían arreglo del lado del proyecto:

- **No descarga el default.** `ollama pull hf.co/ibm-granite/granite-4.1-3b-GGUF:Q3_K_M`, el modelo
  de ADR-0015, falla con `Error: EOF` al leer el manifest, con la imagen 0.32.15 y con la 0.40.1,
  mientras el host llega a huggingface.co sin problema. `make pull-models` no podía instalar el
  modelo por defecto; hubo que copiar los blobs a mano desde otro contenedor.
- **La GPU es frágil.** Con el perfil GPU, Ollama llegó a colgarse en el «GPU discovery
  watchdog» y a caer a CPU sin un error visible. Repartir la VRAM pedía un modelo derivado
  (`bge-m3-cpu`, con `PARAMETER num_gpu 0`) y su propio target.
- **`ollama/ollama:latest` no se fija.** Cada máquina corre la versión que bajó el día del pull
  (en esta convivían cinco imágenes), y cada una pesa más de 5 GB.

Opciones evaluadas:

- **Seguir con Ollama y fijar la versión.** Resuelve el tercer punto, no los dos primeros.
- **llama.cpp en un contenedor** (`ghcr.io/ggml-org/llama.cpp:server-cuda`), como hizo Bonsai
  (ADR-0009). `compose.ministral.yml` documenta por qué se dejó: la imagen del 17/08/2026 no
  cargaba el GGUF oficial de Ministral 3. Además sigue dependiendo del passthrough de la GPU a
  WSL2.
- **llama.cpp nativo en el host.** `llama-server.exe` con CUDA, fuera de Docker, con el build
  fijado.

## Decisión

El LLM y los embeddings los sirven **dos instancias de `llama-server` nativas**, en el host:

- **LLM**: Granite 4.1 3B Q3_K_M en el puerto 8081, en la GPU si hay tarjeta, con dos slots de
  4096 tokens (`-c 8192 -np 2`).
- **Embeddings**: bge-m3 F16 en el puerto 8082, con `--embedding --pooling cls`. Va a la CPU por
  debajo de `KB_VRAM_EMBEDDINGS_GPU` (con `-dev none`, para que ni abra un contexto CUDA).

`scripts/llama-cpp.mjs` instala el build fijado (`b11960`, el de la versión estable v0.8.0,
verificado por SHA-256), arranca, detiene y reporta las dos instancias. `make up` y `make down`
las manejan junto con compose, que queda con `db`, `docling-serve` y `api`. La `api` les habla por
la API compatible con OpenAI (un solo starter de Spring AI) a través de `host.docker.internal`.

Los modelos son archivos GGUF bajo `KB_DATA_DIR/models/`, bajados de Hugging Face con el hash
fijado, como el reranker. Son byte a byte los blobs que tenía Ollama: el de Granite es
`granite-4.1-3b-Q3_K_M.gguf` de `ibm-granite/granite-4.1-3b-GGUF` y el de bge-m3 es
`bge-m3-FP16.gguf` de `gpustack/bge-m3-GGUF` (mismo SHA-256 en los dos lados).

Salen con Ollama los diez perfiles experimentales (`compose.<modelo>.yml`), que dependían de él, y
lo que quedaba de Bonsai. La investigación sigue en `docs/investigacion-vram-y-modelo-llm.md`.

**Medición** (T600 de 4 GB, driver 596, build b11960 CUDA 12.4):

- Embeddings: para los mismos cinco textos, coseno 1,000000 contra los de Ollama en cuatro y
  0,999988 en uno de 3600 caracteres; normalizados en los dos lados. **No hace falta re-ingerir.**
- LLM: 2,4 GB de VRAM con los embeddings en CPU; 6,6–6,9 tokens/s de salida y 11–17 de entrada,
  lo mismo que medía ADR-0015 con Ollama (~7 y ~18).
- Arranque: 3 s hasta responder. La primera vez después de instalar, 70 s de carga y 115 s la
  primera respuesta; desde la segunda, normal. Se explica por la compilación JIT de los kernels de
  CUDA, que queda en caché, aunque no se confirmó en el log.

## Consecuencias

- **A favor**: la descarga del modelo por defecto funciona; la versión del servidor es la misma en
  todas las máquinas; la imagen de Ollama (5 GB) deja de bajarse; el reparto de la GPU es un
  número (`-ngl`) en vez de un modelo derivado; los embeddings no cambian.
- **En contra**: dos procesos del host que nada vigila. Un reinicio de Windows o un
  `docker compose up` a mano dejan la `api` sin modelos. Lo cubren el indicador de salud
  `llamaCpp` (DOWN con el comando para levantarlas), `make llm-estado` y el mensaje de error del
  chat.
- **En contra**: la instalación automática es solo para Windows. En Linux y macOS se usa el
  `llama-server` del PATH (o `LLAMA_SERVER`), porque los builds publicados no traen CUDA. En Linux,
  un servidor en `127.0.0.1` no se ve desde el contenedor: hay que arrancarlo con
  `KB_LLAMA_HOST=0.0.0.0`.
- **En contra**: `llama-server` sirve un solo modelo por instancia. Probar otro LLM es cambiar el
  GGUF (`KB_LLM_GGUF`), no un `docker compose -f`.
- Si alguna vez hace falta servir varios modelos a la vez, el modo router de `llama-server`
  (`--models-dir`) es el siguiente paso, antes que volver a Ollama.
