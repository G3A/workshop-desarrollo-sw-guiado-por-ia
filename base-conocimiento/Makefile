.DEFAULT_GOAL := help
.PHONY: help up imagen gpu-up gpu-check gpu-resumen docling-reciclar cache-reciclar jdk-check up-bonsai down-bonsai up-ministral down-ministral up-qwen35 down-qwen35 up-nemotron down-nemotron up-granite41 down-granite41 up-phi4mini down-phi4mini up-qwen25 down-qwen25 down restart logs ps build test verify pull-models pull-reranker pull-bonsai-gguf pull-ministral pull-qwen35 pull-nemotron pull-granite41 pull-phi4mini pull-qwen25 pin-embeddings-cpu seed vault-init ingest ingest-repos ingest-teams ingest-azdo psql health verificar capturar-error clean format lint secrets check ci hooks



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
ENV_LEIDAS := KB_GPU KB_DOCLING_GPU KB_VRAM_EMBEDDINGS_GPU KB_VRAM_DOCLING_GPU KB_DATA_DIR KB_VAULT_DIR KB_PORT KB_LLM_MODELO
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

## Se le pasa a docker compose SOLO si el archivo existe: --env-file apuntando a
## un archivo ausente no es un no-op, aborta con "env file ... not found" -- y
## arrancar sin archivo de entorno es un caso soportado, porque todos los compose
## traen sus propios defaults.
ENV_FILE_FLAG := $(if $(wildcard $(KB_ENV_FILE)),--env-file $(KB_ENV_FILE),)

COMPOSE           := docker compose $(ENV_FILE_FLAG)
COMPOSE_GPU       := docker compose $(ENV_FILE_FLAG) -f compose.yml -f compose.gpu.yml
## El modelo que descarga `pull-models`. Sale de KB_LLM_MODELO -- el mismo que
## leen los compose y que usa el chat -- para que descargar y ejecutar no puedan
## apuntar a modelos distintos.
##
## Antes era gemma3:4b a secas, sin relacion alguna con KB_LLM_MODELO: el Makefile
## no mencionaba esa variable ni una vez. Con un perfil de Ollama configurado,
## `make pull-models` seguia bajando gemma3:4b y la primera consulta fallaba con
## un 404 del modelo que si hacia falta. Peor todavia porque el indicador de salud
## mandaba justo ahi ("corre `make pull-models`"), asi que el consejo y el comando
## se realimentaban.
##
## OJO con el alcance: esto NO convierte a pull-models en "descarga lo del perfil
## que voy a levantar". El perfil lo elige el target que invocas DESPUES
## (up-ministral, up-granite41...), y cada uno trae su modelo en su propio
## compose, no en KB_LLM_MODELO. Lo que se arregla es que, cuando KB_LLM_MODELO
## esta puesto -- en el entorno o en el archivo de entorno --, se respete en vez
## de ignorarse en silencio. Para los demas perfiles sigue estando su
## `make pull-<perfil>`.
## El entorno se consulta ANTES que el archivo: LLM no se llama KB_LLM_MODELO, asi
## que el `?=` no basta para que una variable de entorno lo pise sola -- hay que
## mirarla explicitamente para mantener la precedencia del resto del Makefile.
LLM         ?= $(if $(KB_LLM_MODELO),$(KB_LLM_MODELO),$(if $(ENV_KB_LLM_MODELO),$(ENV_KB_LLM_MODELO),gemma3:4b))
EMBEDDINGS  ?= bge-m3
KB_DATA_DIR ?= $(if $(ENV_KB_DATA_DIR),$(ENV_KB_DATA_DIR),./.data)
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
## depende de la tarjeta: si hay GPU, con que Compute Capability se compila Bonsai,
## que imagen de CUDA tolera el driver, si los embeddings van a la GPU o a la CPU y
## si docling entra tambien. La idea es que el mismo `make up` haga lo correcto en
## una T600 de 4 GB y en una RTX 3060 de 6 GB sin que nadie edite nada.
##
## Los umbrales no son gustos, salen de lo medido en
## docs/investigacion-vram-y-modelo-llm.md:
##
## - KB_VRAM_EMBEDDINGS_GPU. En la T600 de 4 GB, `gemma3:4b` NO entra completo ni
##   estando solo: Ollama offloadea capas hasta que caben y queda en 40% GPU /
##   60% CPU (hallazgo 1). Ahi darle VRAM a bge-m3 solo empeora al LLM, y moverlo
##   a CPU es una mejora neta medida (hallazgo 2). Con 6 GB o mas entran los dos
##   y los embeddings se quedan en la tarjeta, que es lo que se quiere para la
##   ingesta.
## - KB_VRAM_DOCLING_GPU. Medido en la sesion 27 (hallazgos 106 y 109): docling
##   pica en 2.2 GB con un documento grande. Sumado a los 4.0 GB de gemma3:4b a
##   contexto 4096 y los ~1.2 GB de bge-m3 en GPU, son ~7.4 GB con las tres etapas
##   en la tarjeta -- por eso 8192 y no 6144: en 6 GB los tres no entran. El LLM
##   esta ocioso durante la ingesta pero sigue residente (OLLAMA_KEEP_ALIVE), asi
##   que la suma es real. Ademas docling no libera la VRAM entre conversiones (bug
##   conocido sin fix, ver ADR-0010 y compose.docling-gpu.yml). Con KB_DOCLING_GPU=1
##   se fuerza igual, y con 0 se apaga.
##
## Los dos umbrales son variables: si mides otra cosa en tu equipo, cambialos.
KB_VRAM_EMBEDDINGS_GPU ?= $(if $(ENV_KB_VRAM_EMBEDDINGS_GPU),$(ENV_KB_VRAM_EMBEDDINGS_GPU),6144)
KB_VRAM_DOCLING_GPU    ?= $(if $(ENV_KB_VRAM_DOCLING_GPU),$(ENV_KB_VRAM_DOCLING_GPU),8192)

## Devuelve, en una sola linea: vram_mib cc driver_major embeddings docling cuda_tag emb_en_env
## Sin GPU (o sin nvidia-smi) devuelve la fila neutra "0 75 0 cpu no 12.6.0 ...".
GPU_PLAN := $(shell \
  info=$$(nvidia-smi --query-gpu=memory.total,compute_cap,driver_version --format=csv,noheader,nounits 2>/dev/null | head -1); \
  if grep -sqE '^[[:space:]]*KB_EMBEDDINGS_MODELO=' "$(KB_ENV_FILE)"; then fijo=si; else fijo=no; fi; \
  if [ -z "$$info" ]; then echo "0 75 0 cpu no 12.6.0 $$fijo"; exit 0; fi; \
  vram=$$(echo "$$info" | cut -d, -f1 | tr -cd '0-9'); \
  cc=$$(echo   "$$info" | cut -d, -f2 | tr -cd '0-9'); \
  drv=$$(echo  "$$info" | cut -d, -f3 | cut -d. -f1 | tr -cd '0-9'); \
  [ -n "$$vram" ] || vram=0; [ -n "$$cc" ] || cc=75; [ -n "$$drv" ] || drv=0; \
  if [ "$$vram" -ge $(KB_VRAM_EMBEDDINGS_GPU) ]; then emb=gpu; else emb=cpu; fi; \
  if [ "$$vram" -ge $(KB_VRAM_DOCLING_GPU) ];    then doc=si; else doc=no; fi; \
  if   [ "$$drv" -ge 560 ]; then tag=12.6.0; \
  elif [ "$$drv" -ge 550 ]; then tag=12.4.1; \
  elif [ "$$drv" -ge 535 ]; then tag=12.2.2; \
  else tag=12.6.0; fi; \
  echo "$$vram $$cc $$drv $$emb $$doc $$tag $$fijo")

GPU_VRAM_MIB   := $(word 1,$(GPU_PLAN))
GPU_CC         := $(word 2,$(GPU_PLAN))
GPU_DRIVER_MAJ := $(word 3,$(GPU_PLAN))
GPU_EMBEDDINGS := $(word 4,$(GPU_PLAN))
GPU_DOCLING    := $(word 5,$(GPU_PLAN))
GPU_CUDA_TAG   := $(word 6,$(GPU_PLAN))
GPU_EMB_FIJADO := $(word 7,$(GPU_PLAN))

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
## conversiones (bug conocido sin fix, ADR-0010), asi que cuando Ollama va a
## cargar sus modelos ya no queda sitio y cae a 100% CPU -- los DOS, incluido
## bge-m3, que con 1.2 GB cabria de sobra en 6 GB.
##
## Lo que se ve es "todo va lentisimo" y un `ollama ps` diciendo 100% CPU, con
## `make gpu-check` informando "Perfil de compose: GPU", la GPU correctamente
## reservada en el contenedor y `nvidia-smi` funcionando dentro. Nada apunta a
## docling. Diagnosticarlo costo varias rondas.
DOCLING_GPU_FORZADO := $(if $(and $(filter 1,$(KB_DOCLING_GPU)),$(filter-out 0,$(GPU_VRAM_MIB))),$(shell [ "$(GPU_VRAM_MIB)" -lt "$(KB_VRAM_DOCLING_GPU)" ] && echo si),)


## Sin GPU no hay nada que repartir: los embeddings van a CPU igual, pero por la via
## normal de Ollama, no con el modelo -cpu fijado.
ifndef HAY_GPU
GPU_EMBEDDINGS := cpu-sin-gpu
GPU_DOCLING := no
endif

## docling en GPU es un override mas encadenado al perfil GPU, no un compose aparte.
ifeq ($(GPU_DOCLING),si)
COMPOSE_GPU := $(COMPOSE_GPU) -f compose.docling-gpu.yml
endif

## Compute Capability y tag de CUDA para el perfil Bonsai, derivados del hardware
## real en vez de fijados a la T600 del ADR-0009. `?=` para que un valor puesto a
## mano (o en .env) siga ganando.
##   cc:  75 = Turing (T600, RTX 20xx)  86 = Ampere (RTX 30xx)
##        89 = Ada (RTX 40xx)           120 = Blackwell (RTX 50xx)
##   tag: cada imagen nvidia/cuda exige un driver minimo y lo verifica al arrancar
##        el contenedor. 12.6.0 pide >= 560, 12.4.1 >= 550, 12.2.2 >= 535.
BONSAI_CUDA_ARCH     ?= $(GPU_CC)
BONSAI_CUDA_TAG      ?= $(GPU_CUDA_TAG)
BONSAI_DRIVER_MINIMO := 560
export BONSAI_CUDA_ARCH
export BONSAI_CUDA_TAG

## Embeddings: se exporta el modelo elegido SOLO si no esta fijado en .env. docker
## compose le da precedencia al entorno sobre .env, asi que exportarlo siempre
## pisaria una eleccion explicita del usuario. Con .env callado, manda el hardware.
ifeq ($(GPU_EMB_FIJADO),no)
ifeq ($(GPU_EMBEDDINGS),cpu)
export KB_EMBEDDINGS_MODELO := $(EMBEDDINGS)-cpu
else ifeq ($(GPU_EMBEDDINGS),gpu)
export KB_EMBEDDINGS_MODELO := $(EMBEDDINGS)
endif
endif

ifeq ($(HAY_GPU),1)
COMPOSE_ACTIVO := $(COMPOSE_GPU)
else
COMPOSE_ACTIVO := $(COMPOSE)
endif

## ---------------------------------------------------------------- perfiles de modelo
##
## Van despues del bloque de GPU a proposito: encadenan compose.gpu.yml SOLO si hay
## tarjeta. Antes lo hacian siempre, y en un equipo sin GPU eso hace fallar el `up`
## entero -- la reserva de dispositivo nvidia no es negociable para docker, aunque
## Ollama sepa caer a CPU perfectamente. Con esto, `make up-ministral` se adapta al
## hardware igual que `make up`.
##
## Bonsai es la excepcion y sigue exigiendo GPU siempre: su llama-server se compila
## con CUDA y reserva la tarjeta completa para si mismo (ver ADR-0009), no tiene
## sentido levantarlo sin ella.
##
## Ninguno encadena compose.docling-gpu.yml: son perfiles de LLM, y el reparto de
## docling se decide aparte en `up`.
PERFIL_GPU := $(if $(HAY_GPU),-f compose.gpu.yml,)

COMPOSE_BONSAI    := docker compose $(ENV_FILE_FLAG) -f compose.yml -f compose.gpu.yml -f compose.bonsai.yml
# Los que siguen se sirven desde el `ollama` de siempre, no de un llama-server
# aparte -- ver compose.ministral.yml sobre por que se dejo de usar llama-server, y
# compose.qwen35.yml sobre el estado experimental de ese perfil. Granite, Phi-4 y
# Qwen2.5 son de la sesion 25 (piloto de 100 preguntas con sintesis estructurada
# contra los candidatos descartados por "texto pegado").
COMPOSE_MINISTRAL := docker compose $(ENV_FILE_FLAG) -f compose.yml $(PERFIL_GPU) -f compose.ministral.yml
COMPOSE_QWEN35    := docker compose -f compose.yml $(PERFIL_GPU) -f compose.qwen35.yml
COMPOSE_NEMOTRON  := docker compose -f compose.yml $(PERFIL_GPU) -f compose.nemotron.yml
COMPOSE_GRANITE41 := docker compose -f compose.yml $(PERFIL_GPU) -f compose.granite41.yml
COMPOSE_PHI4MINI  := docker compose -f compose.yml $(PERFIL_GPU) -f compose.phi4mini.yml
COMPOSE_QWEN25    := docker compose -f compose.yml $(PERFIL_GPU) -f compose.qwen25.yml

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

help:  ## Muestra esta ayuda
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

## ---------------------------------------------------------------- infraestructura


## ---------------------------------------------------------------- arranque con aviso
##
## Los nueve `up` no invocan a docker compose directamente: pasan por `arrancar`,
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
( $(1) up -d --build 2>&1; echo $$? > "$$est" ) | tee "$$log"; \
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

up:  ## Levanta los 4 servicios y reparte la GPU segun la tarjeta que detecte
	@$(call arrancar,$(COMPOSE_ACTIVO))
	@$(MAKE) --no-print-directory gpu-resumen

gpu-up:  ## Fuerza el perfil GPU aunque la deteccion automatica no encuentre nvidia-smi
	@$(call arrancar,$(COMPOSE_GPU))
	@$(MAKE) --no-print-directory gpu-resumen

gpu-resumen:  ## Muestra en una pantalla que se llevo la GPU y que quedo en CPU
ifeq ($(HAY_GPU),1)
	@echo "Perfil activo: GPU (compose.gpu.yml) -- $$(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -1), $(GPU_VRAM_MIB) MiB"
	@echo "  LLM (consultas)          : GPU"
	@echo "  Embeddings (ingesta y consultas): $(if $(filter gpu,$(GPU_EMBEDDINGS)),GPU,CPU con $(EMBEDDINGS)-cpu -- la VRAM no alcanza para el LLM y los embeddings a la vez)"
	@echo "  Extraccion PDF (docling) : $(if $(filter si,$(GPU_DOCLING)),GPU,CPU)"
	@echo "  Reranker (cross-encoder) : CPU siempre -- el proyecto usa la build CPU de ONNX Runtime"
ifeq ($(GPU_EMB_FIJADO),si)
	@echo ""
	@echo "  Nota: KB_EMBEDDINGS_MODELO esta fijado en tu .env, asi que manda eso y no la"
	@echo "        deteccion. Comentalo para que make elija segun el hardware."
endif
	@echo ""
ifeq ($(DOCLING_GPU_FORZADO),si)
	@echo ""
	@echo "  ATENCION: KB_DOCLING_GPU=1 mete docling en la GPU con solo $(GPU_VRAM_MIB) MiB,"
	@echo "  por debajo del umbral de $(KB_VRAM_DOCLING_GPU) MiB. docling NO libera la VRAM entre"
	@echo "  conversiones (ADR-0010), asi que puede quedarse con la tarjeta y dejar"
	@echo "  a Ollama SIN sitio -- se cae a 100% CPU en silencio, sin ningun error."
	@echo ""
	@echo "  Si las consultas van lentisimas, comprueba el reparto real:"
	@echo "      docker exec kb-ollama ollama ps      (mira la columna PROCESSOR)"
	@echo "  Para revertirlo, quita KB_DOCLING_GPU de tu archivo de entorno o:"
	@echo "      KB_DOCLING_GPU=0 make down && KB_DOCLING_GPU=0 make up"
endif
	@echo "  Detalle y umbrales: make gpu-check"
else
	@echo "Perfil activo: CPU (compose.yml) -- sin GPU NVIDIA activa"
	@echo "  Si esta maquina tiene una, corre: make gpu-check"
endif

gpu-check:  ## Diagnostica que hardware vio make y como repartio la GPU
	@echo "== Como esta corriendo make =="
	@echo "  Shell            : $(SHELL)"
	@echo "  KB_GPU           : $(if $(KB_GPU),$(KB_GPU),sin definir)"
	@echo "  KB_DOCLING_GPU   : $(if $(KB_DOCLING_GPU),$(KB_DOCLING_GPU),sin definir)"
	@echo ""
	@echo "== Hardware detectado =="
	-@nvidia-smi --query-gpu=name,memory.total,compute_cap,driver_version --format=csv || echo "   nvidia-smi no respondio"
	-@command -v nvidia-smi || echo "   nvidia-smi no esta en el PATH de este shell"
	@echo ""
	@echo "== Lo que make dedujo =="
	@echo "  VRAM             : $(GPU_VRAM_MIB) MiB"
	@echo "  Compute Cap      : $(GPU_CC)   -> BONSAI_CUDA_ARCH=$(BONSAI_CUDA_ARCH)"
	@echo "  Driver (mayor)   : $(GPU_DRIVER_MAJ) -> BONSAI_CUDA_TAG=$(BONSAI_CUDA_TAG)"
	@echo "  Perfil de compose: $(if $(HAY_GPU),GPU,CPU)"
	@echo ""
	@echo "== Reparto de la tarjeta =="
	@echo "  LLM                      : $(if $(HAY_GPU),GPU,CPU)"
	@echo "  Embeddings               : $(if $(filter gpu,$(GPU_EMBEDDINGS)),GPU,CPU)   (umbral KB_VRAM_EMBEDDINGS_GPU=$(KB_VRAM_EMBEDDINGS_GPU) MiB)"
	@echo "  Extraccion PDF (docling) : $(if $(filter si,$(GPU_DOCLING)),GPU,CPU)   (umbral KB_VRAM_DOCLING_GPU=$(KB_VRAM_DOCLING_GPU) MiB)$(if $(filter si,$(DOCLING_GPU_FORZADO)),  <-- FORZADO por debajo del umbral,)"
	@echo "  Reranker                 : CPU   (build CPU de ONNX Runtime, no lo cambia ninguna variable)"
	@echo "  KB_EMBEDDINGS_MODELO     : $(if $(filter si,$(GPU_EMB_FIJADO)),fijado en tu .env -- manda eso,$(KB_EMBEDDINGS_MODELO) (elegido por make))"
	@echo ""
	@echo "== Por que =="
	@echo "  Con menos de $(KB_VRAM_EMBEDDINGS_GPU) MiB, gemma3:4b no entra completo ni estando solo"
	@echo "  (medido en la T600 de 4 GB: 40% GPU / 60% CPU), asi que darle VRAM a los"
	@echo "  embeddings solo empeora al LLM: se los manda a CPU, que es una mejora neta"
	@echo "  medida. Con $(KB_VRAM_EMBEDDINGS_GPU) MiB o mas entran los dos y los embeddings se quedan en la"
	@echo "  tarjeta. docling entra recien con $(KB_VRAM_DOCLING_GPU) MiB porque no libera su VRAM entre"
	@echo "  conversiones (bug conocido, ver ADR-0010). Ver docs/investigacion-vram-y-modelo-llm.md."
	@echo ""
	@echo "  Todo eso se puede forzar:  KB_GPU=1|0   KB_DOCLING_GPU=1|0"
	@echo "                             KB_VRAM_EMBEDDINGS_GPU=...   KB_VRAM_DOCLING_GPU=..."
	@echo ""
ifeq ($(DOCLING_GPU_FORZADO),si)
	@echo ""
	@echo "  ATENCION: KB_DOCLING_GPU=1 mete docling en la GPU con solo $(GPU_VRAM_MIB) MiB,"
	@echo "  por debajo del umbral de $(KB_VRAM_DOCLING_GPU) MiB. docling NO libera la VRAM entre"
	@echo "  conversiones (ADR-0010), asi que puede quedarse con la tarjeta y dejar"
	@echo "  a Ollama SIN sitio -- se cae a 100% CPU en silencio, sin ningun error."
	@echo ""
	@echo "  Si las consultas van lentisimas, comprueba el reparto real:"
	@echo "      docker exec kb-ollama ollama ps      (mira la columna PROCESSOR)"
	@echo "  Para revertirlo, quita KB_DOCLING_GPU de tu archivo de entorno o:"
	@echo "      KB_DOCLING_GPU=0 make down && KB_DOCLING_GPU=0 make up"
endif
	@echo "  Si la tarjeta aparece arriba pero el perfil dice CPU, fallo la deteccion, no"
	@echo "  el hardware: levanta con  KB_GPU=1 make up"


up-bonsai:  ## Levanta el perfil Bonsai-8B (LLM 1-bit via llama-server); requiere GPU NVIDIA y el GGUF de pull-bonsai-gguf
	@echo "Bonsai: imagen base nvidia/cuda:$(BONSAI_CUDA_TAG) (requiere driver NVIDIA >= $(BONSAI_DRIVER_MINIMO)) compilando para sm_$(BONSAI_CUDA_ARCH)."
	@echo "Si falla con \"unsatisfied condition: cuda>=12.6\" o el modelo no corre en la GPU corre: make gpu-check"
	@# Sin el GGUF, el bind mount de compose.bonsai.yml crea el directorio VACIO
	@# (create_host_path) en vez de fallar: llama-server arranca, no encuentra el
	@# modelo, sale, y como tiene restart: unless-stopped entra en bucle sin pasar
	@# nunca el healthcheck. Lo que ve quien lo corre es "dependency failed to
	@# start: container kb-llama-server is unhealthy" -- un error que apunta a la
	@# api y no menciona el archivo que falta. El motivo real solo aparece en
	@# `docker logs kb-llama-server`. Este chequeo lo dice antes de compilar.
	@if [ ! -f "$(KB_DATA_DIR)/bonsai/Bonsai-8B-Q1_0.gguf" ]; then \
		echo ""; \
		echo "ERROR: falta el modelo en $(KB_DATA_DIR)/bonsai/Bonsai-8B-Q1_0.gguf"; \
		echo "  Descargalo una sola vez (~1.16 GB) con:  make pull-bonsai-gguf"; \
		exit 1; \
	fi
	@$(call arrancar,$(COMPOSE_BONSAI))

down-bonsai:  ## Detiene el perfil Bonsai (mismos -f que up-bonsai, para no dejar contenedores huerfanos)
	$(COMPOSE_BONSAI) down

up-ministral:  ## Levanta el perfil Ministral 3B (LLM via Ollama); corre make pull-ministral antes la primera vez
	@$(call arrancar,$(COMPOSE_MINISTRAL))

down-ministral:  ## Detiene el perfil Ministral (mismos -f que up-ministral)
	$(COMPOSE_MINISTRAL) down

up-qwen35:  ## Levanta el perfil experimental Qwen3.5 4B (LLM via Ollama, fix de thinking integrado); corre make pull-qwen35 antes la primera vez
	@$(call arrancar,$(COMPOSE_QWEN35))

down-qwen35:  ## Detiene el perfil Qwen3.5 (mismos -f que up-qwen35)
	$(COMPOSE_QWEN35) down

up-nemotron:  ## Levanta el perfil experimental Nemotron-mini 4B (LLM via Ollama, sin thinking); corre make pull-nemotron antes la primera vez
	@$(call arrancar,$(COMPOSE_NEMOTRON))

down-nemotron:  ## Detiene el perfil Nemotron-mini (mismos -f que up-nemotron)
	$(COMPOSE_NEMOTRON) down

up-granite41:  ## Levanta el perfil experimental Granite 4.1 3B; corre make pull-granite41 antes la primera vez
	@$(call arrancar,$(COMPOSE_GRANITE41))

down-granite41:  ## Detiene el perfil Granite 4.1 (mismos -f que up-granite41)
	$(COMPOSE_GRANITE41) down

up-phi4mini:  ## Levanta el perfil experimental Phi-4 Mini 3.8B; corre make pull-phi4mini antes la primera vez
	@$(call arrancar,$(COMPOSE_PHI4MINI))

down-phi4mini:  ## Detiene el perfil Phi-4 Mini (mismos -f que up-phi4mini)
	$(COMPOSE_PHI4MINI) down

up-qwen25:  ## Levanta el perfil experimental Qwen2.5 3B; corre make pull-qwen25 antes la primera vez
	@$(call arrancar,$(COMPOSE_QWEN25))

down-qwen25:  ## Detiene el perfil Qwen2.5 (mismos -f que up-qwen25)
	$(COMPOSE_QWEN25) down

down:  ## Detiene los servicios (los datos en KB_DATA_DIR sobreviven); --remove-orphans limpia si venis de un perfil Bonsai/Ministral
	$(COMPOSE_ACTIVO) down --remove-orphans

restart:  ## Reinicia solo la api, sin tocar db ni ollama
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

health:  ## Reporte de salud detallado: db, ollama y modelos faltantes
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

pull-models:  ## Descarga el modelo del chat (KB_LLM_MODELO, por defecto gemma3:4b), embeddings y reranker (~5.5 GB). Otros perfiles: make pull-<perfil>
	$(COMPOSE_ACTIVO) exec ollama ollama pull $(LLM)
	$(COMPOSE_ACTIVO) exec ollama ollama pull $(EMBEDDINGS)
	$(MAKE) pull-reranker
ifeq ($(GPU_EMBEDDINGS),cpu)
	@echo ""
	@echo "Esta tarjeta ($(GPU_VRAM_MIB) MiB) no da para el LLM y los embeddings a la vez, asi que"
	@echo "make va a pedir $(EMBEDDINGS)-cpu. Se crea ahora, para que no falte al arrancar:"
	@$(MAKE) --no-print-directory pin-embeddings-cpu
endif
	@echo ""
	@echo "Listo. 'make gpu-check' muestra como quedo repartida la tarjeta."

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

pull-bonsai-gguf:  ## Descarga el GGUF de Bonsai-8B (~1.16 GB) a KB_DATA_DIR, una sola vez (perfil up-bonsai)
	@mkdir -p "$(KB_DATA_DIR)/bonsai"
	@if [ -f "$(KB_DATA_DIR)/bonsai/Bonsai-8B-Q1_0.gguf" ]; then \
		echo "El GGUF ya esta en $(KB_DATA_DIR)/bonsai, no se descarga de nuevo."; \
	else \
		echo "Descargando Bonsai-8B-Q1_0.gguf a $(KB_DATA_DIR)/bonsai ..."; \
		curl -fL --progress-bar -o "$(KB_DATA_DIR)/bonsai/Bonsai-8B-Q1_0.gguf" \
			https://huggingface.co/prism-ml/Bonsai-8B-gguf/resolve/main/Bonsai-8B-Q1_0.gguf; \
	fi

pull-ministral:  ## Descarga el modelo Ministral 3B a Ollama (~2 GB via Hugging Face), una sola vez (perfil up-ministral)
	$(COMPOSE_ACTIVO) exec ollama ollama pull hf.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF:Q4_K_M

pull-qwen35:  ## Descarga el modelo Qwen3.5 4B a Ollama (~3.4 GB), una sola vez (perfil up-qwen35)
	$(COMPOSE_ACTIVO) exec ollama ollama pull qwen3.5:4b

pull-nemotron:  ## Descarga el modelo Nemotron-mini 4B a Ollama (~2.7 GB), una sola vez (perfil up-nemotron)
	$(COMPOSE_ACTIVO) exec ollama ollama pull nemotron-mini:4b

pull-granite41:  ## Descarga Granite 4.1 3B a Ollama (~2.1 GB), una sola vez (perfil up-granite41)
	$(COMPOSE_ACTIVO) exec ollama ollama pull granite4.1:3b

pull-phi4mini:  ## Descarga Phi-4 Mini 3.8B a Ollama (~2.5 GB), una sola vez (perfil up-phi4mini)
	$(COMPOSE_ACTIVO) exec ollama ollama pull phi4-mini:3.8b

pull-qwen25:  ## Descarga Qwen2.5 3B a Ollama (~1.9 GB), una sola vez (perfil up-qwen25)
	$(COMPOSE_ACTIVO) exec ollama ollama pull qwen2.5:3b

pin-embeddings-cpu:  ## Crea bge-m3-cpu, fijado a CPU, para dejarle toda la VRAM al LLM
	@# Por debajo de KB_VRAM_EMBEDDINGS_GPU el LLM no entra completo ni estando solo
	@# (medido en la T600 de 4 GB: 40% GPU / 60% CPU), asi que quitarle memoria para
	@# los embeddings solo lo empeora. Van a CPU, donde el AVX-512 con VNNI los hace
	@# baratos, y la GPU queda para la sintesis, que es la etapa que se espera.
	@# Ya no hace falta correrlo a mano: pull-models lo hace cuando la tarjeta lo pide.
	$(COMPOSE_ACTIVO) exec -T ollama sh -c 'printf "FROM $(EMBEDDINGS)\nPARAMETER num_gpu 0\n" > /tmp/Modelfile.cpu && ollama create $(EMBEDDINGS)-cpu -f /tmp/Modelfile.cpu'
	@echo ""
	@echo "Listo: $(EMBEDDINGS)-cpu creado. No hace falta tocar nada mas -- make lo selecciona"
	@echo "solo mientras no fijes KB_EMBEDDINGS_MODELO a mano en tu archivo de entorno."

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


imagen:  ## Reconstruye la imagen de kb-api sin levantar nada (make up y los up-<perfil> ya lo hacen solos)
	@# Existe para cuando se quiere construir SIN tocar lo que esta corriendo (por
	@# ejemplo, dejar la imagen lista antes de un `down`/`up` mas tarde) o para
	@# comprobar que el codigo compila dentro de Docker. No hace falta correrlo
	@# antes de un up: todos los up de este Makefile pasan `--build`.
	$(COMPOSE) build api

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

secrets:  ## Scan the working tree for committed secrets
	gitleaks detect --no-banner --redact

check: lint build test  ## Single local confidence signal
	@echo "OK -- the repo is green"

ci: lint build test secrets  ## What the CI pipeline runs
	@echo "OK -- CI gates passed"

hooks:  ## Install git hooks (Lefthook)
	lefthook install
