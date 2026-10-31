.DEFAULT_GOAL := help
.PHONY: help up gpu-check gpu-resumen docling-reciclar cache-reciclar jdk-check llm-instalar llm-up llm-down llm-estado down restart logs ps build test verify pull-models pull-gguf pull-reranker seed vault-init ingest ingest-repos ingest-teams ingest-azdo psql health verificar capturar-error clean format lint test-js secrets check ci hooks



## ---------------------------------------------------------------- shell en Windows
##
## Las recetas de este Makefile son sh: pipes, `if [ -f ... ]`, `command -v`, `$$(...)`.
## GNU Make en Windows usa sh.exe como shell SOLO si lo encuentra en el PATH, y cae a
## cmd.exe si no. Desde PowerShell el PATH trae `C:\Program Files\Git\cmd` (git.exe)
## pero NO `Git\usr\bin` (sh.exe): sin esto, Make cae a cmd y practicamente cada receta
## falla con "no se reconoce como un comando interno o externo".
##
## Y hay un segundo efecto, menos obvio: make.exe de ezwinports es de 32 bits, asi que
## sus procesos hijo sufren la redireccion de sistema de archivos de Windows y
## C:\Windows\System32 se resuelve como SysWOW64 -- donde no existe nvidia-smi.exe. Esa
## es la razon real por la que `make up` decia "Perfil activo: CPU" en maquinas con
## GPU cuando se lo invocaba desde PowerShell. Un sh.exe de 64 bits no sufre esa
## redireccion, asi que fijar SHELL aca arregla las recetas Y la deteccion de GPU.
##
## Se usan rutas 8.3 (PROGRA~1) a proposito: un SHELL con espacios rompe en varias
## versiones de Make para Windows.
ifeq ($(OS),Windows_NT)
SH_CANDIDATOS := C:/PROGRA~1/Git/usr/bin/sh.exe C:/PROGRA~2/Git/usr/bin/sh.exe $(subst \,/,$(LOCALAPPDATA))/Programs/Git/usr/bin/sh.exe
SH_ENCONTRADO := $(firstword $(wildcard $(SH_CANDIDATOS)))
ifneq ($(SH_ENCONTRADO),)
SHELL := $(SH_ENCONTRADO)
.SHELLFLAGS := -c
## Fijar SHELL no alcanza: sh.exe arranca con el PATH que le pasa Make, y el de
## PowerShell no incluye Git\usr\bin, asi que `grep`, `awk`, `curl` y `sha256sum`
## no existen para las recetas ("grep: command not found"). Se antepone ese
## directorio, pero SOLO cuando el PATH viene en formato Windows (separado por
## ";", o sea Make invocado desde PowerShell o cmd). Desde Git Bash el PATH ya es
## POSIX y ya trae /usr/bin: ahi tocarlo lo romperia.
ifneq ($(findstring ;,$(PATH)),)
export PATH := $(patsubst %/,%,$(dir $(SH_ENCONTRADO)));$(PATH)
endif
else
$(warning No se encontro el sh.exe de Git for Windows. Make va a caer a cmd.exe y las)
$(warning recetas de este Makefile no van a funcionar. Instala Git for Windows, o corre)
$(warning make desde Git Bash.)
endif
endif

## ---------------------------------------------------------------- archivo de entorno
##
## make NO carga el archivo de entorno solo -- eso lo hace docker compose. Hasta
## que este bloque existio, eso significaba que un KB_DATA_DIR / KB_VAULT_DIR /
## KB_PORT puesto ahi lo respetaba compose y lo ignoraba make, y las dos mitades
## trabajaban sobre valores distintos EN SILENCIO:
##
##   - `make pull-bonsai-gguf` bajaba el GGUF a ./.data mientras el contenedor
##     montaba otra carpeta -- y despues el chequeo de up-bonsai lo declaraba
##     ausente estando descargado.
##   - `make vault-init` copiaba el corpus a un vault que nadie monta, y la
##     ingesta seguia encontrando cero documentos.
##   - `make health` / `make ingest` consultaban el puerto equivocado.
##
## Una sola lectura del archivo, misma filosofia que GPU_PLAN mas abajo. Solo se
## leen las variables que make necesita ANTES de invocar a docker compose; las
## que solo consume el contenedor se quedan como estan, que ya funciona.
##
## Precedencia resultante, la misma que aplica docker compose:
##   entorno / linea de comandos  >  archivo de entorno  >  default del Makefile
##
## El sed recorta el comentario final, los espacios y las comillas envolventes, y
## se queda con la ULTIMA aparicion, que es la que gana en docker compose.
KB_ENV_FILE ?= .env
ENV_LEIDAS := KB_GPU KB_DOCLING_GPU KB_VRAM_EMBEDDINGS_GPU KB_VRAM_DOCLING_GPU KB_DATA_DIR KB_VAULT_DIR KB_PORT KB_LLM_MODELO KB_LLM_PORT KB_EMBEDDINGS_PORT KB_LLM_CONTEXTO_TOKENS KB_LLM_PARALELO KB_LLM_GGUF KB_LLAMA_HOST
ENV_PLAN := $(shell \
  for v in $(ENV_LEIDAS); do \
    val=""; \
    if [ -f "$(KB_ENV_FILE)" ]; then \
      val=$$(sed -nE "s/^[[:space:]]*$$v=//p" "$(KB_ENV_FILE)" | tail -1 \
            | sed -E "s/[[:space:]]+#.*$$//; s/^[[:space:]]*//; s/[[:space:]]*$$//; s/^\"(.*)\"$$/\1/"); \
    fi; \
    [ -n "$$val" ] || val="-"; \
    printf "%s " "$$val"; \
  done)

ENV_KB_GPU                 := $(patsubst -,,$(word 1,$(ENV_PLAN)))
ENV_KB_DOCLING_GPU         := $(patsubst -,,$(word 2,$(ENV_PLAN)))
ENV_KB_VRAM_EMBEDDINGS_GPU := $(patsubst -,,$(word 3,$(ENV_PLAN)))
ENV_KB_VRAM_DOCLING_GPU    := $(patsubst -,,$(word 4,$(ENV_PLAN)))
ENV_KB_DATA_DIR            := $(patsubst -,,$(word 5,$(ENV_PLAN)))
ENV_KB_VAULT_DIR           := $(patsubst -,,$(word 6,$(ENV_PLAN)))
ENV_KB_PORT                := $(patsubst -,,$(word 7,$(ENV_PLAN)))
ENV_KB_LLM_MODELO          := $(patsubst -,,$(word 8,$(ENV_PLAN)))
ENV_KB_LLM_PORT            := $(patsubst -,,$(word 9,$(ENV_PLAN)))
ENV_KB_EMBEDDINGS_PORT     := $(patsubst -,,$(word 10,$(ENV_PLAN)))
ENV_KB_LLM_CONTEXTO_TOKENS := $(patsubst -,,$(word 11,$(ENV_PLAN)))
ENV_KB_LLM_PARALELO        := $(patsubst -,,$(word 12,$(ENV_PLAN)))
ENV_KB_LLM_GGUF            := $(patsubst -,,$(word 13,$(ENV_PLAN)))
ENV_KB_LLAMA_HOST          := $(patsubst -,,$(word 14,$(ENV_PLAN)))

## Se le pasa a docker compose SOLO si el archivo existe: --env-file apuntando a
## un archivo ausente no es un no-op, aborta con "env file ... not found" -- y
## arrancar sin archivo de entorno es un caso soportado, porque todos los compose
## traen sus propios defaults.
ENV_FILE_FLAG := $(if $(wildcard $(KB_ENV_FILE)),--env-file $(KB_ENV_FILE),)

COMPOSE           := docker compose $(ENV_FILE_FLAG)
KB_DATA_DIR ?= $(if $(ENV_KB_DATA_DIR),$(ENV_KB_DATA_DIR),./.data)

## ---------------------------------------------------------------- modelos (ADR-0017)
##
## El LLM y los embeddings no corren en Docker: los sirven dos instancias de
## llama-server en el host, que maneja scripts/llama-cpp.mjs. Los modelos son
## archivos GGUF bajo KB_DATA_DIR/models, bajados de Hugging Face con el hash
## fijado, igual que el reranker. Son byte a byte los mismos que servia Ollama
## (sus blobs se llaman por ese hash), asi que el indice no cambia.
LLM_GGUF_URL           := https://huggingface.co/ibm-granite/granite-4.1-3b-GGUF/resolve/main/granite-4.1-3b-Q3_K_M.gguf
LLM_GGUF               := $(KB_DATA_DIR)/models/llm/granite-4.1-3b-Q3_K_M.gguf
LLM_GGUF_SHA256        := 43db9c8da5360eaeef0039cdc28aefdf43ede98ddf36ec7edfd6d4ee89f36403
EMBEDDINGS_GGUF_URL    := https://huggingface.co/gpustack/bge-m3-GGUF/resolve/main/bge-m3-FP16.gguf
EMBEDDINGS_GGUF        := $(KB_DATA_DIR)/models/embeddings/bge-m3-FP16.gguf
EMBEDDINGS_GGUF_SHA256 := daec91ffb5dd0c27411bd71f29932917c49cf529a641d0168496c3a501e3062c
LLAMA_CPP              := node scripts/llama-cpp.mjs
# Mismo default que compose.yml, y con el mismo motivo: el vault vive FUERA del
# repo. Se declara aca para que vault-init sepa donde copiar el corpus de ejemplo.
KB_VAULT_DIR ?= $(if $(ENV_KB_VAULT_DIR),$(ENV_KB_VAULT_DIR),../../vault)
KB_PORT     ?= $(if $(ENV_KB_PORT),$(ENV_KB_PORT),8080)

# El pom compila a release 25. Se verifica en jdk-check, del que dependen los
# targets que compilan -- Maven solo se entera despues de resolver dependencias.

JAVA_MINIMO := 25
## ---------------------------------------------------------------- GPU: hardware y reparto
##
## Una sola llamada a nvidia-smi por invocacion de make, de la que sale TODO lo que
## depende de la tarjeta: si hay GPU (y entonces el LLM va a ella), si los
## embeddings van a la GPU o a la CPU y si docling entra tambien. La idea es que el
## mismo `make up` haga lo correcto en una T600 de 4 GB y en una RTX 3060 de 6 GB
## sin que nadie edite nada.
##
## Los umbrales no son gustos, salen de lo medido en
## docs/investigacion-vram-y-modelo-llm.md:
##
## - KB_VRAM_EMBEDDINGS_GPU. En la T600 de 4 GB, `gemma3:4b` NO entraba completo
##   ni estando solo (40% GPU / 60% CPU, hallazgo 1), y darle VRAM a bge-m3 solo
##   empeoraba al LLM: moverlo a CPU fue una mejora neta medida (hallazgo 2).
##   Granite 4.1 3B Q3_K_M si entra (2.4 GB medidos en la T600 con llama-server
##   y dos slots de 4096); bge-m3 en F16 pesa 1.2 GB mas su buffer, y juntos no
##   se midieron en 4 GB: el umbral sigue siendo el de gemma3:4b. Con 6 GB o mas
##   entran los dos y los embeddings se quedan en la tarjeta, que es lo que se
##   quiere para la ingesta.
## - KB_VRAM_DOCLING_GPU. Medido en la sesion 27 (hallazgos 106 y 109): docling
##   pica en 2.2 GB con un documento grande. Sumado a los 4.0 GB de gemma3:4b a
##   contexto 4096 y los ~1.2 GB de bge-m3 en GPU, son ~7.4 GB con las tres etapas
##   en la tarjeta -- por eso 8192 y no 6144: en 6 GB los tres no entran. El LLM
##   esta ocioso durante la ingesta pero sigue residente (llama-server lo carga al arrancar), asi
##   que la suma es real. Ademas docling no libera la VRAM entre conversiones (bug
##   conocido sin fix, ver ADR-0010 y compose.docling-gpu.yml). Con KB_DOCLING_GPU=1
##   se fuerza igual, y con 0 se apaga.
##
## Los dos umbrales son variables: si mides otra cosa en tu equipo, cambialos.
KB_VRAM_EMBEDDINGS_GPU ?= $(if $(ENV_KB_VRAM_EMBEDDINGS_GPU),$(ENV_KB_VRAM_EMBEDDINGS_GPU),6144)
KB_VRAM_DOCLING_GPU    ?= $(if $(ENV_KB_VRAM_DOCLING_GPU),$(ENV_KB_VRAM_DOCLING_GPU),8192)

## Devuelve, en una sola linea: vram_mib embeddings docling
## Sin GPU (o sin nvidia-smi) devuelve la fila neutra "0 cpu no".
GPU_PLAN := $(shell \
  vram=$$(nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>/dev/null | head -1 | tr -cd '0-9'); \
  [ -n "$$vram" ] || vram=0; \
  if [ "$$vram" -gt 0 ] && [ "$$vram" -ge $(KB_VRAM_EMBEDDINGS_GPU) ]; then emb=gpu; else emb=cpu; fi; \
  if [ "$$vram" -gt 0 ] && [ "$$vram" -ge $(KB_VRAM_DOCLING_GPU) ];    then doc=si; else doc=no; fi; \
  echo "$$vram $$emb $$doc")

GPU_VRAM_MIB   := $(word 1,$(GPU_PLAN))
GPU_EMBEDDINGS := $(word 2,$(GPU_PLAN))
GPU_DOCLING    := $(word 3,$(GPU_PLAN))

## Estas dos se consultan con ifeq y no con ?=, asi que el valor del archivo de
## entorno se aplica aca: ifndef respeta lo que venga del entorno o de la linea
## de comandos, que es la precedencia que queremos.
ifndef KB_GPU
KB_GPU := $(ENV_KB_GPU)
endif
ifndef KB_DOCLING_GPU
KB_DOCLING_GPU := $(ENV_KB_DOCLING_GPU)
endif

## KB_GPU manda sobre la deteccion: KB_GPU=1 fuerza el perfil GPU, KB_GPU=0 lo apaga.
## Sigue existiendo como salida de emergencia, pero desde que el Makefile fija su
## propio SHELL (ver arriba) la deteccion ya no depende de desde donde se invoque.
ifeq ($(KB_GPU),1)
HAY_GPU := 1
else ifeq ($(KB_GPU),0)
HAY_GPU :=
else
HAY_GPU := $(if $(filter-out 0,$(GPU_VRAM_MIB)),1,)
endif

ifeq ($(KB_DOCLING_GPU),1)
GPU_DOCLING := si
endif
ifeq ($(KB_DOCLING_GPU),0)
GPU_DOCLING := no
endif

## KB_DOCLING_GPU=1 pisa el umbral, y eso puede costar la GPU entera. Se marca
## aqui para poder avisarlo, porque el sintoma no se parece en nada a la causa.
##
## Medido en un equipo real (RTX 3060 Laptop, 6144 MiB): con esta variable a 1 en
## el archivo de entorno, `make` encadena compose.docling-gpu.yml pese a estar por
## debajo de KB_VRAM_DOCLING_GPU. docling toma VRAM y NO la suelta entre
## conversiones (bug conocido sin fix, ADR-0010), asi que cuando el LLM va a
## cargar ya no queda sitio. Con Ollama eso era caer a 100% CPU en silencio, sin
## un solo error -- "todo va lentisimo" y nada apuntaba a docling. llama-server
## no cae solo: con -ngl 99 y sin VRAM falla al arrancar, y `make up` muestra su
## log.
DOCLING_GPU_FORZADO := $(if $(and $(filter 1,$(KB_DOCLING_GPU)),$(filter-out 0,$(GPU_VRAM_MIB))),$(shell [ "$(GPU_VRAM_MIB)" -lt "$(KB_VRAM_DOCLING_GPU)" ] && echo si),)


## Sin GPU no hay nada que repartir: todo va a CPU.
ifndef HAY_GPU
GPU_EMBEDDINGS := cpu
GPU_DOCLING := no
endif

## docling en GPU es el unico override de compose que queda: el LLM y los
## embeddings toman la tarjeta desde el host, no desde un contenedor.
ifeq ($(GPU_DOCLING),si)
COMPOSE_ACTIVO := $(COMPOSE) -f compose.yml -f compose.docling-gpu.yml
else
COMPOSE_ACTIVO := $(COMPOSE)
endif

## Lo que scripts/llama-cpp.mjs necesita saber del reparto. 99 = todas las capas en
## la GPU; 0 = CPU (el script agrega -dev none para que ni abra un contexto CUDA).
## Sin GPU se instala el build CPU de llama.cpp en vez del CUDA (~25 MB contra 670).
export KB_DATA_DIR
export KB_LLM_GPU_CAPAS        := $(if $(HAY_GPU),99,0)
export KB_EMBEDDINGS_GPU_CAPAS := $(if $(filter gpu,$(GPU_EMBEDDINGS)),99,0)
export KB_LLAMA_CPP_VARIANTE   := $(if $(HAY_GPU),cuda,cpu)
## Las del archivo de entorno que el script lee, con la misma precedencia de siempre:
## entorno > archivo > default (el del script).
$(foreach v,KB_LLM_MODELO KB_LLM_PORT KB_EMBEDDINGS_PORT KB_LLM_CONTEXTO_TOKENS KB_LLM_PARALELO KB_LLM_GGUF KB_LLAMA_HOST,\
  $(if $($(v)),,$(if $(ENV_$(v)),$(eval export $(v) := $(ENV_$(v))))))

# Re-subida ONNX de la comunidad, no del repo oficial de BAAI: el hash fijado
# es lo que hace que "confiar en esta descarga" sea una decision verificable
# y no un acto de fe. Si BAAI publica alguna vez un ONNX propio, cambia esto.
RERANKER_REPO             := https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/resolve/main
RERANKER_DIR              := $(KB_DATA_DIR)/models/reranker
RERANKER_MODEL_SHA256     := 912fc1215c2dbff6499700534bd8d31253af01573861abbfc43afd1fab6cce5d
RERANKER_TOKENIZER_SHA256 := 8bf8afbfd11306bd872018c53bfdf2e160a56f8edbcf49933324404791c148d3

define verificar_sha256
	if command -v sha256sum >/dev/null 2>&1; then \
		calculado=$$(sha256sum "$(1)" | cut -d' ' -f1); \
	else \
		calculado=$$(shasum -a 256 "$(1)" | cut -d' ' -f1); \
	fi; \
	if [ "$$calculado" != "$(2)" ]; then \
		echo "ERROR: hash de $(1) no coincide."; \
		echo "  esperado:  $(2)"; \
		echo "  calculado: $$calculado"; \
		rm -f "$(1)"; \
		exit 1; \
	fi
endef

## Baja un GGUF a un .tmp, verifica el hash y solo entonces lo deja en su lugar:
## un archivo a medias nunca queda con el nombre que llama-server busca.
define bajar_gguf
	mkdir -p "$(dir $(1))"; \
	if [ -f "$(1)" ]; then \
		echo "$(notdir $(1)) ya esta en $(dir $(1)), no se descarga de nuevo."; \
	else \
		echo "Descargando $(notdir $(1)) a $(dir $(1)) ..."; \
		curl -fL --progress-bar -o "$(1).tmp" "$(2)" && \
		$(call verificar_sha256,$(1).tmp,$(3)) && \
		mv "$(1).tmp" "$(1)" && \
		echo "$(notdir $(1)) listo y verificado."; \
	fi
endef

help:  ## Muestra esta ayuda
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

## ---------------------------------------------------------------- infraestructura


## ---------------------------------------------------------------- arranque con aviso
##
## `up` no invoca a docker compose directamente: pasa por `arrancar`,
## que vigila UN fallo concreto y lo traduce.
##
## El fallo: la etapa `deps` del Dockerfile corre `dependency:go-offline` sobre un
## cache mount de BuildKit (id=maven-repo), y la etapa `build` compila con `-o`
## (offline) confiando en que ahi esta todo. Pero la CAPA y el MOUNT tienen vidas
## separadas: la capa se marca CACHED y sobrevive, mientras el GC de BuildKit
## puede vaciar el mount cuando le hace falta espacio. Cuando eso pasa,
## `go-offline` NO se vuelve a ejecutar -- su capa esta cacheada -- y la etapa 2
## arranca sin un solo artefacto:
##
##   #14 [deps 6/6] RUN ... dependency:go-offline
##   #14 CACHED                                      <- no se ejecuto
##   #18 [build 2/2] RUN ... ./mvnw -o -B -q clean package -DskipTests
##   [ERROR] Cannot access central ... in offline mode and the artifact
##           com.google.errorprone:error_prone_annotations:jar:2.33.0
##           has not been downloaded from it before.
##
## Ese mensaje no dice en ningun lado que la solucion sea reciclar el cache de
## build, y el nombre del artefacto cambia segun cual pida Maven primero -- lo
## que hace que parezca un problema de dependencias del proyecto, que no es.
##
## La salida se muestra en vivo (tee) y NO se decide por el codigo de salida
## solo: se busca la firma del error en el log. El codigo se recoge aparte,
## dentro del subshell, porque el estado de una tuberia es el del ultimo comando
## (tee, siempre 0) y `set -o pipefail` no es portable a todas las sh.
define arrancar
log=$$(mktemp); est=$$(mktemp); \
( $(1) up -d --build --remove-orphans 2>&1; echo $$? > "$$est" ) | tee "$$log"; \
codigo=$$(cat "$$est"); \
if [ "$$codigo" != "0" ] && grep -qE "in offline mode and the artifact|has not been downloaded from it before" "$$log"; then \
  echo ""; \
  echo "  =================================================================="; \
  echo "  El build fallo porque el cache de Maven quedo vacio, NO por una"; \
  echo "  dependencia rota del proyecto."; \
  echo ""; \
  echo "  La capa de descarga sigue marcada CACHED, asi que no se reintenta"; \
  echo "  sola. Hay que invalidarla:"; \
  echo ""; \
  echo "      make cache-reciclar"; \
  echo "      make $@"; \
  echo ""; \
  echo "  La primera vez despues de eso tarda ~11 min en volver a bajar las"; \
  echo "  dependencias. Las siguientes vuelven a ser segundos."; \
  echo "  =================================================================="; \
  echo ""; \
fi; \
rm -f "$$log" "$$est"; \
exit $$codigo
endef

up:  ## Levanta llama-server (LLM y embeddings, en el host) y los 3 contenedores; reparte la GPU sola
	@$(LLAMA_CPP) arrancar
	@$(call arrancar,$(COMPOSE_ACTIVO))
	@$(MAKE) --no-print-directory gpu-resumen

gpu-resumen:  ## Muestra en una pantalla que se llevo la GPU y que quedo en CPU
ifeq ($(HAY_GPU),1)
	@echo "GPU: $$(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -1), $(GPU_VRAM_MIB) MiB"
	@echo "  LLM (llama-server, host)        : GPU"
	@echo "  Embeddings (llama-server, host) : $(if $(filter gpu,$(GPU_EMBEDDINGS)),GPU,CPU -- con menos de $(KB_VRAM_EMBEDDINGS_GPU) MiB la VRAM queda para el LLM)"
	@echo "  Extraccion PDF (docling)        : $(if $(filter si,$(GPU_DOCLING)),GPU,CPU)"
	@echo "  Reranker (cross-encoder)        : CPU siempre -- el proyecto usa la build CPU de ONNX Runtime"
	@echo ""
ifeq ($(DOCLING_GPU_FORZADO),si)
	@echo "  ATENCION: KB_DOCLING_GPU=1 mete docling en la GPU con solo $(GPU_VRAM_MIB) MiB,"
	@echo "  por debajo del umbral de $(KB_VRAM_DOCLING_GPU) MiB. docling NO libera la VRAM entre"
	@echo "  conversiones (ADR-0010) y puede dejar al LLM sin sitio la proxima vez que arranque."
	@echo "  Para revertirlo, quita KB_DOCLING_GPU de tu archivo de entorno o:"
	@echo "      KB_DOCLING_GPU=0 make down && KB_DOCLING_GPU=0 make up"
	@echo ""
endif
	@echo "  Detalle y umbrales: make gpu-check"
else
	@echo "Sin GPU NVIDIA activa: LLM, embeddings y docling en CPU"
	@echo "  Si esta maquina tiene una, corre: make gpu-check"
endif

gpu-check:  ## Diagnostica que hardware vio make y como repartio la GPU
	@echo "== Como esta corriendo make =="
	@echo "  Shell            : $(SHELL)"
	@echo "  KB_GPU           : $(if $(KB_GPU),$(KB_GPU),sin definir)"
	@echo "  KB_DOCLING_GPU   : $(if $(KB_DOCLING_GPU),$(KB_DOCLING_GPU),sin definir)"
	@echo ""
	@echo "== Hardware detectado =="
	-@nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv || echo "   nvidia-smi no respondio"
	-@command -v nvidia-smi || echo "   nvidia-smi no esta en el PATH de este shell"
	@echo ""
	@echo "== Lo que make dedujo =="
	@echo "  VRAM             : $(GPU_VRAM_MIB) MiB"
	@echo "  GPU              : $(if $(HAY_GPU),si,no)   -> build de llama.cpp: $(KB_LLAMA_CPP_VARIANTE)"
	@echo ""
	@echo "== Reparto de la tarjeta =="
	@echo "  LLM                      : $(if $(HAY_GPU),GPU,CPU)   (KB_LLM_GPU_CAPAS=$(KB_LLM_GPU_CAPAS))"
	@echo "  Embeddings               : $(if $(filter gpu,$(GPU_EMBEDDINGS)),GPU,CPU)   (umbral KB_VRAM_EMBEDDINGS_GPU=$(KB_VRAM_EMBEDDINGS_GPU) MiB)"
	@echo "  Extraccion PDF (docling) : $(if $(filter si,$(GPU_DOCLING)),GPU,CPU)   (umbral KB_VRAM_DOCLING_GPU=$(KB_VRAM_DOCLING_GPU) MiB)$(if $(filter si,$(DOCLING_GPU_FORZADO)),  <-- FORZADO por debajo del umbral,)"
	@echo "  Reranker                 : CPU   (build CPU de ONNX Runtime, no lo cambia ninguna variable)"
	@echo ""
	@echo "== Por que =="
	@echo "  Con menos de $(KB_VRAM_EMBEDDINGS_GPU) MiB el LLM y los embeddings no se midieron juntos en la"
	@echo "  tarjeta (con gemma3:4b no entraban: 40% GPU / 60% CPU), asi que los embeddings van a CPU y la"
	@echo "  VRAM queda para el LLM, que es la etapa que se espera. docling entra recien con"
	@echo "  $(KB_VRAM_DOCLING_GPU) MiB porque no libera su VRAM entre conversiones (bug conocido, ver ADR-0010)."
	@echo "  Ver docs/investigacion-vram-y-modelo-llm.md."
	@echo ""
	@echo "  Todo eso se puede forzar:  KB_GPU=1|0   KB_DOCLING_GPU=1|0"
	@echo "                             KB_VRAM_EMBEDDINGS_GPU=...   KB_VRAM_DOCLING_GPU=..."
	@echo ""
	@echo "  Si la tarjeta aparece arriba pero GPU dice no, fallo la deteccion, no el"
	@echo "  hardware: levanta con  KB_GPU=1 make up"

llm-instalar:  ## Baja y verifica el build fijado de llama.cpp (Windows; en Linux/macOS usa el del PATH)
	@$(LLAMA_CPP) instalar

llm-up:  ## Levanta solo las dos instancias de llama-server (LLM y embeddings), sin tocar Docker
	@$(LLAMA_CPP) arrancar

llm-down:  ## Detiene las dos instancias de llama-server
	@$(LLAMA_CPP) detener

llm-estado:  ## Dice si cada instancia de llama-server corre y responde, y donde esta su log
	@$(LLAMA_CPP) estado

down:  ## Detiene los contenedores y llama-server (los datos en KB_DATA_DIR sobreviven)
	$(COMPOSE_ACTIVO) down --remove-orphans
	@$(LLAMA_CPP) detener

restart:  ## Reinicia solo la api, sin tocar db ni llama-server
	$(COMPOSE_ACTIVO) up -d --build api

logs:  ## Sigue el log de la api
	$(COMPOSE_ACTIVO) logs -f api

ps:  ## Estado de los contenedores
	$(COMPOSE_ACTIVO) ps

verificar:  ## Diagnostica por que responde "No encontre informacion" (usa PREGUNTA="...")
	@# Se invoca con [scriptblock]::Create y NO con `powershell -File`, a proposito.
	@# La politica de ejecucion de PowerShell se aplica a ARCHIVOS: con RemoteSigned
	@# (el default de Windows) mas la marca de descarga, o con AllSigned, un .ps1 del
	@# repo se rechaza con "no esta firmado digitalmente. No se puede ejecutar este
	@# script en el sistema actual" -- un error que suena a permisos y no lo es.
	@# `-ExecutionPolicy Bypass` arregla ese caso, pero NO cuando la politica viene
	@# impuesta por directiva de grupo (ambitos MachinePolicy/UserPolicy), que la
	@# linea de comandos no puede pisar. Un scriptblock creado desde texto no es un
	@# archivo, asi que no pasa por esa comprobacion: funciona en los tres casos.
	@# Verificado forzando -ExecutionPolicy AllSigned.
	@KB_PORT=$(KB_PORT) powershell -NoProfile -Command "& ([scriptblock]::Create((Get-Content -Raw 'scripts/verificar-respuesta-vacia.ps1'))) '$(PREGUNTA)'"

LINEAS ?= 300
capturar-error:  ## Vuelca la excepcion de kb-api a un archivo para compartir (usa LINEAS=1000)
	@# Mismo rodeo que `verificar`: scriptblock en vez de `powershell -File`, para
	@# no chocar con la politica de ejecucion. Ver el comentario de arriba.
	@powershell -NoProfile -Command "& ([scriptblock]::Create((Get-Content -Raw 'scripts/capturar-error-api.ps1'))) -Lineas $(LINEAS)"

health:  ## Reporte de salud detallado: db, llama-server (LLM y embeddings) y reranker
	@curl -fsS http://localhost:$(KB_PORT)/actuator/health | python -m json.tool 2>/dev/null \
	  || curl -fsS http://localhost:$(KB_PORT)/actuator/health

cache-reciclar:  ## Vacia el cache de build de BuildKit; usalo cuando `up` avise de "offline mode"
	@# Existe para UN fallo concreto, el que detecta `arrancar` (ver su comentario):
	@# el cache mount de Maven (id=maven-repo) queda vacio mientras la capa que lo
	@# llena sigue marcada CACHED, asi que `dependency:go-offline` no se reintenta
	@# y la etapa 2 compila con -o sin artefactos. Invalidar el cache de build es
	@# lo que fuerza a esa capa a volver a ejecutarse.
	@#
	@# `docker builder prune -f` se lleva TODO el cache de build, no solo el mount
	@# de este proyecto -- BuildKit no permite podar un mount suelto por su id. Por
	@# eso el target avisa de lo que cuesta antes de hacerlo, en vez de esconderlo:
	@# el siguiente build de CUALQUIER proyecto de esta maquina tambien empieza de
	@# cero.
	@echo "Vaciando el cache de build de BuildKit."
	@echo "  Ojo: se lleva el cache de TODOS los proyectos de esta maquina, no solo"
	@echo "  el de este. El siguiente build aqui tarda ~11 min en rebajar las"
	@echo "  dependencias de Maven; los de otros proyectos, lo que les toque."
	@echo "  No toca imagenes, contenedores ni volumenes de datos."
	@echo ""
	@docker builder prune -f
	@echo ""
	@echo "Listo. Ahora: make up   (o el perfil que estuvieras levantando)"

docling-reciclar:  ## Reinicia docling-serve para liberar la VRAM que retiene entre conversiones
	@# docling-serve no libera la VRAM al terminar una conversion (docling-serve#233,
	@# abierta desde junio 2025 sin PR; #440, que pide un limite configurable, tambien
	@# sigue abierta). Reiniciar el proceso es la unica forma de recuperarla: no hay
	@# manera de liberarla en caliente. Solo tiene sentido con docling en GPU; en CPU
	@# no hace dano pero tampoco sirve de nada.
	@echo "VRAM antes:"
	-@nvidia-smi --query-gpu=memory.used,memory.total --format=csv || echo "   nvidia-smi no respondio"
	$(COMPOSE_ACTIVO) restart docling-serve
	@echo "VRAM despues:"
	-@nvidia-smi --query-gpu=memory.used,memory.total --format=csv || echo "   nvidia-smi no respondio"

## ---------------------------------------------------------------- modelos

pull-models:  ## Instala llama.cpp y baja Granite 4.1 3B Q3_K_M, bge-m3 y el reranker, verificados (~3.4 GB)
	@$(MAKE) --no-print-directory llm-instalar
	@$(MAKE) --no-print-directory pull-gguf
	@$(MAKE) --no-print-directory pull-reranker
	@echo ""
	@echo "Listo. 'make up' levanta todo; 'make gpu-check' muestra como queda repartida la tarjeta."

pull-gguf:  ## Baja y verifica los GGUF del LLM y de los embeddings (no los vuelve a bajar si ya estan)
	@$(call bajar_gguf,$(LLM_GGUF),$(LLM_GGUF_URL),$(LLM_GGUF_SHA256))
	@$(call bajar_gguf,$(EMBEDDINGS_GGUF),$(EMBEDDINGS_GGUF_URL),$(EMBEDDINGS_GGUF_SHA256))
pull-reranker:  ## Descarga y verifica el ONNX del cross-encoder (~545 MB)
	@mkdir -p "$(RERANKER_DIR)"
	@if [ -f "$(RERANKER_DIR)/model.onnx" ] && [ -f "$(RERANKER_DIR)/tokenizer.json" ]; then \
		echo "El reranker ya esta en $(RERANKER_DIR), no se descarga de nuevo."; \
	else \
		echo "Descargando reranker (bge-reranker-v2-m3, cuantizado int8) a $(RERANKER_DIR) ..."; \
		curl -fL --progress-bar -o "$(RERANKER_DIR)/model.onnx.tmp" "$(RERANKER_REPO)/onnx/model_int8.onnx" && \
		curl -fL --progress-bar -o "$(RERANKER_DIR)/tokenizer.json.tmp" "$(RERANKER_REPO)/tokenizer.json" && \
		$(call verificar_sha256,$(RERANKER_DIR)/model.onnx.tmp,$(RERANKER_MODEL_SHA256)) && \
		$(call verificar_sha256,$(RERANKER_DIR)/tokenizer.json.tmp,$(RERANKER_TOKENIZER_SHA256)) && \
		mv "$(RERANKER_DIR)/model.onnx.tmp" "$(RERANKER_DIR)/model.onnx" && \
		mv "$(RERANKER_DIR)/tokenizer.json.tmp" "$(RERANKER_DIR)/tokenizer.json" && \
		echo "Reranker listo y verificado."; \
	fi

vault-init:  ## Crea el vault y copia corpus/ a vault/documentos (no pisa lo que ya este)
	@# El corpus de ejemplo viene versionado en corpus/, pero el vault vive FUERA
	@# del repo (KB_VAULT_DIR, ver .env.example) y NADA conectaba las dos rutas.
	@# Como el bind mount de compose.yml tiene create_host_path, un vault
	@# inexistente se creaba VACIO en vez de fallar: `make seed` ingeria cero
	@# documentos sin error, y despues toda pregunta respondia el
	@# MENSAJE_SIN_INFORMACION del Orquestador -- correctamente, porque no habia
	@# nada ingerido. Sin un solo error en ningun log.
	@mkdir -p "$(KB_VAULT_DIR)/documentos" "$(KB_VAULT_DIR)/repos"
	@cp -n corpus/* "$(KB_VAULT_DIR)/documentos/" 2>/dev/null || true
	@echo "Vault en $(KB_VAULT_DIR) -- documentos/:"
	@ls -1 "$(KB_VAULT_DIR)/documentos" 2>/dev/null | sed 's/^/  /' || echo "  (vacio)"

seed:  ## Prepara el vault con el corpus de ejemplo y lo ingiere (vault-init + ingest)
	@$(MAKE) --no-print-directory vault-init
	@$(MAKE) --no-print-directory ingest

ingest:  ## Ingiere vault/documentos: documentos nuevos o cambiados quedan troceados y encolados para embeber
	@curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/local-docs | python -m json.tool 2>/dev/null \
	  || curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/local-docs

ingest-repos:  ## Ingiere vault/repos (F6): repos Git locales nuevos o cambiados
	@curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/repos-locales | python -m json.tool 2>/dev/null \
	  || curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/repos-locales

ingest-teams:  ## Ingiere el canal de Teams configurado (F6, no-op si KB_GRAPH_HABILITADO=false)
	@curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/teams-graph | python -m json.tool 2>/dev/null \
	  || curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/teams-graph

ingest-azdo:  ## Ingiere work items y wiki de Azure DevOps (F6, no-op si KB_AZDO_HABILITADO=false)
	@curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/azure-devops | python -m json.tool 2>/dev/null \
	  || curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/azure-devops

## ---------------------------------------------------------------- desarrollo

jdk-check:  ## Verifica que el JDK activo pueda compilar el proyecto (release $(JAVA_MINIMO))
	@java_bin="java"; \
	if [ -n "$$JAVA_HOME" ]; then java_bin="$$JAVA_HOME/bin/java"; fi; \
	version=$$("$$java_bin" -version 2>&1 | head -1 | sed -E 's/^[^"]*"([0-9]+).*/\1/'); \
	case "$$version" in \
	  ''|*[!0-9]*) \
	    echo "ERROR: no se pudo determinar la version de Java ejecutando: $$java_bin -version"; \
	    echo "  JAVA_HOME = $${JAVA_HOME:-<sin definir; se uso el java del PATH>}"; \
	    exit 1;; \
	esac; \
	if [ "$$version" -lt $(JAVA_MINIMO) ]; then \
	  echo "ERROR: este proyecto compila a release $(JAVA_MINIMO) y el JDK activo es $$version."; \
	  echo "  JAVA_HOME = $${JAVA_HOME:-<sin definir; se uso el java del PATH>}"; \
	  echo ""; \
	  echo "Maven fallaria con \"release version $(JAVA_MINIMO) not supported\" recien despues de"; \
	  echo "resolver las dependencias. Apunta JAVA_HOME a un JDK $(JAVA_MINIMO) o mas nuevo:"; \
	  echo ""; \
	  echo "  PowerShell:  \$$env:JAVA_HOME = 'C:\\Program Files\\Eclipse Adoptium\\jdk-25...'"; \
	  echo "  Git Bash:    export JAVA_HOME='/c/Program Files/Eclipse Adoptium/jdk-25...'"; \
	  echo ""; \
	  echo "Para dejarlo fijo en Windows: Configuracion > Variables de entorno."; \
	  exit 1; \
	fi


build: jdk-check  ## Compila el jar sin correr pruebas
	sh ./mvnw -B clean package -DskipTests

test: jdk-check  ## Corre las pruebas, incluidos los gates de arquitectura
	sh ./mvnw -B test

verify: jdk-check  ## Build completo con todos los gates
	sh ./mvnw -B clean verify

psql:  ## Abre una sesion psql contra la base
	$(COMPOSE_ACTIVO) exec db psql -U $${POSTGRES_USER:-kb} -d $${POSTGRES_DB:-baseconocimiento}

clean:  ## Borra artefactos de compilacion (NO toca los modelos ni la base)
	sh ./mvnw -B clean

## ---------------------------------------------------------------- quality gates
##
## Nota Windows (instrument-project-java): el `./mvnw` bare de mas abajo (y el de build/test/
## verify/clean de arriba) va prefijado con `sh` a proposito. En esta maquina, GNU Make para
## Windows (ezwinports, el que la propia skill recomienda via winget) ejecuta una receta que
## empieza con `./algo` en forma directa en vez de pasarla por el shell configurado, y `./` no
## se resuelve asi -- confirmado con un Makefile minimo. `sh ./mvnw` fuerza el paso por el
## interprete y funciona. En Linux/macOS o en CI (GitHub Actions, ubuntu-latest) el prefijo no
## hace falta pero tampoco rompe nada.

format:  ## Apply code formatting (Spotless)
	sh ./mvnw -q spotless:apply

lint:  ## Verify code style and static rules (gate: Spotless + Checkstyle)
	sh ./mvnw -q spotless:check checkstyle:check

# Las pruebas del JavaScript de la UI (issue #54): node:test, sin dependencias ni package.json.
# Exige node en el PATH; en CI es el que trae ubuntu-latest (ci.yml no instala uno). El glob va sin
# comillas a proposito: lo expande sh, asi no depende de que la version de node acepte globs.
test-js:  ## Corre las pruebas del JavaScript de la UI (node --test)
	node --test src/test/js/*.test.js

secrets:  ## Scan the working tree for committed secrets
	gitleaks detect --no-banner --redact

check: lint build test test-js  ## Single local confidence signal
	@echo "OK -- the repo is green"

ci: lint build test test-js secrets  ## What the CI pipeline runs
	@echo "OK -- CI gates passed"

hooks:  ## Install git hooks (Lefthook)
	lefthook install
