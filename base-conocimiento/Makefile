.DEFAULT_GOAL := help
.PHONY: help up gpu-up gpu-check jdk-check up-bonsai down-bonsai up-ministral down-ministral up-qwen35 down-qwen35 up-nemotron down-nemotron up-granite41 down-granite41 up-phi4mini down-phi4mini up-qwen25 down-qwen25 down restart logs ps build test verify pull-models pull-reranker pull-bonsai-gguf pull-ministral pull-qwen35 pull-nemotron pull-granite41 pull-phi4mini pull-qwen25 pin-embeddings-cpu seed ingest ingest-repos ingest-teams ingest-azdo psql health clean format lint secrets check ci hooks



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

COMPOSE           := docker compose
COMPOSE_GPU       := docker compose -f compose.yml -f compose.gpu.yml
# Bonsai sirve el LLM desde un llama-server aparte (ver ADR-0009), que reserva
# la GPU completa para si mismo -- no tiene sentido levantarlo sin GPU, a
# diferencia de `up`, que si detecta su ausencia y usa CPU. Ministral en cambio
# se sirve desde el mismo `ollama` de siempre (ver compose.ministral.yml sobre
# por que se dejo de usar llama-server) -- sigue encadenando compose.gpu.yml
# aca por consistencia con el resto de los perfiles GPU, pero a diferencia de
# Bonsai no es un requisito duro: Ollama cae a CPU solo si no hay tarjeta.
COMPOSE_BONSAI    := docker compose -f compose.yml -f compose.gpu.yml -f compose.bonsai.yml
COMPOSE_MINISTRAL := docker compose -f compose.yml -f compose.gpu.yml -f compose.ministral.yml
# Mismo caso que Ministral: se sirve desde el `ollama` de siempre, no de un
# llama-server aparte -- ver compose.qwen35.yml sobre el estado experimental
# de este perfil (fix de "thinking" integrado, sin piloto de evaluacion propio
# todavia).
COMPOSE_QWEN35    := docker compose -f compose.yml -f compose.gpu.yml -f compose.qwen35.yml
# Mismo caso: se sirve desde el `ollama` de siempre -- ver compose.nemotron.yml.
COMPOSE_NEMOTRON  := docker compose -f compose.yml -f compose.gpu.yml -f compose.nemotron.yml
# Mismo caso, los tres: se sirven desde el `ollama` de siempre. Perfiles nuevos
# de la sesion 25 para el piloto de 100 preguntas con sintesis estructurada
# contra los candidatos descartados por "texto pegado" que no tenian compose
# dedicado todavia (probados originalmente con overrides sueltos, sesiones 2-9).
COMPOSE_GRANITE41 := docker compose -f compose.yml -f compose.gpu.yml -f compose.granite41.yml
COMPOSE_PHI4MINI  := docker compose -f compose.yml -f compose.gpu.yml -f compose.phi4mini.yml
COMPOSE_QWEN25    := docker compose -f compose.yml -f compose.gpu.yml -f compose.qwen25.yml
LLM         ?= gemma3:4b
EMBEDDINGS  ?= bge-m3
KB_DATA_DIR ?= ./.data
KB_PORT     ?= 8080

# El pom compila a release 25. Se verifica en jdk-check, del que dependen los
# targets que compilan -- Maven solo se entera despues de resolver dependencias.
JAVA_MINIMO := 25

# Imagen base de CUDA y Compute Capability con las que se compila llama.cpp en
# Dockerfile.bonsai. Los defaults son los de la GPU de referencia del ADR-0009
# (T600, Turing) -- si tu tarjeta es otra, esto NO es opcional:
#
#   BONSAI_CUDA_ARCH: 75 = Turing (T600, RTX 20xx) | 86 = Ampere (RTX 30xx)
#                     89 = Ada (RTX 40xx)          | 120 = Blackwell (RTX 50xx)
#
#   BONSAI_CUDA_TAG:  la imagen nvidia/cuda exige un driver minimo y lo verifica
#                     en runtime. 12.6.0 pide driver >= 560; con uno anterior el
#                     contenedor ni arranca ("unsatisfied condition: cuda>=12.6"),
#                     despues de haber compilado ~20 minutos. Bajar este tag es la
#                     salida si no puedes actualizar el driver del equipo.
#
# Se exportan para que docker compose las vea: compose.bonsai.yml las lee como
# ${BONSAI_CUDA_ARCH} / ${BONSAI_CUDA_TAG}, y una variable de Make no llega al
# entorno del proceso hijo si no se exporta.
BONSAI_CUDA_ARCH     ?= 75
BONSAI_CUDA_TAG      ?= 12.6.0
BONSAI_DRIVER_MINIMO := 560
export BONSAI_CUDA_ARCH
export BONSAI_CUDA_TAG

# Deteccion automatica de GPU NVIDIA: nvidia-smi solo existe y responde si hay
# tarjeta y el driver esta instalado (con nvidia-container-toolkit, Docker ya
# puede reservarla). Si el resultado es "1", todos los targets de infra usan
# compose.gpu.yml sin que haga falta pedirlo a mano.
#
# La deteccion depende del shell con el que Make evalua $(shell ...): `command -v`
# es de sh, no de cmd. En un Windows donde Make no encuentra un sh POSIX -- o donde
# nvidia-smi no esta en el PATH de ESE shell, aunque si lo este en la terminal --
# esto da vacio y `make up` cae a CPU en una maquina que si tiene tarjeta, sin decir
# por que. `make gpu-check` muestra que vio Make; KB_GPU es la salida manual:
#   KB_GPU=1 make up   fuerza el perfil GPU
#   KB_GPU=0 make up   lo apaga aunque haya tarjeta
DETECCION_GPU := $(shell command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1 && echo 1)

ifeq ($(KB_GPU),1)
HAY_GPU := 1
else ifeq ($(KB_GPU),0)
HAY_GPU :=
else
HAY_GPU := $(DETECCION_GPU)
endif

ifeq ($(HAY_GPU),1)
COMPOSE_ACTIVO := $(COMPOSE_GPU)
else
COMPOSE_ACTIVO := $(COMPOSE)
endif

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

up:  ## Levanta los 4 servicios; usa la GPU NVIDIA automaticamente si el host tiene una
	$(COMPOSE_ACTIVO) up -d --build
	@echo "Perfil activo: $(if $(HAY_GPU),GPU (compose.gpu.yml) -- Ollama con VRAM reservada,CPU (compose.yml) -- sin GPU NVIDIA activa; si esta maquina tiene una corre make gpu-check)"

gpu-up:  ## Fuerza el perfil GPU aunque la deteccion automatica no encuentre nvidia-smi
	$(COMPOSE_GPU) up -d --build

gpu-check:  ## Diagnostica la deteccion de GPU: por que dio lo que dio y como forzarla
	@echo "Shell que usa Make    : $(SHELL)"
	@echo "KB_GPU (override)     : $(if $(KB_GPU),$(KB_GPU),sin definir)"
	@echo "Deteccion automatica  : $(if $(DETECCION_GPU),ok -- nvidia-smi existe y respondio,vacia -- no se pudo ejecutar nvidia-smi desde Make)"
	@echo "Perfil que usaria up  : $(if $(HAY_GPU),GPU (compose.gpu.yml),CPU (compose.yml))"
	@echo ""
	@echo "-- ruta de nvidia-smi --"
	-@command -v nvidia-smi || echo "   no esta en el PATH de este shell"
	@echo "-- tarjetas --"
	-@nvidia-smi -L || echo "   nvidia-smi no respondio"
	@echo "-- driver --"
	-@nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv || echo "   nvidia-smi no respondio"
	@echo ""
	@echo "Si la tarjeta aparece arriba pero el perfil dice CPU, la deteccion es lo que"
	@echo "fallo, no el hardware: levanta con  KB_GPU=1 make up"
	@echo ""
	@echo "Para el perfil Bonsai (make up-bonsai) ademas hace falta driver >= $(BONSAI_DRIVER_MINIMO),"
	@echo "que es lo que exige la imagen base nvidia/cuda:$(BONSAI_CUDA_TAG), y BONSAI_CUDA_ARCH"
	@echo "igual a la Compute Capability de tu tarjeta (hoy: $(BONSAI_CUDA_ARCH))."

up-bonsai:  ## Levanta el perfil Bonsai-8B (LLM 1-bit via llama-server); requiere GPU NVIDIA y el GGUF de pull-bonsai-gguf
	@echo "Bonsai: imagen base nvidia/cuda:$(BONSAI_CUDA_TAG) (requiere driver NVIDIA >= $(BONSAI_DRIVER_MINIMO)) compilando para sm_$(BONSAI_CUDA_ARCH)."
	@echo "Si falla con \"unsatisfied condition: cuda>=12.6\" o el modelo no corre en la GPU corre: make gpu-check"
	$(COMPOSE_BONSAI) up -d --build

down-bonsai:  ## Detiene el perfil Bonsai (mismos -f que up-bonsai, para no dejar contenedores huerfanos)
	$(COMPOSE_BONSAI) down

up-ministral:  ## Levanta el perfil Ministral 3B (LLM via Ollama); corre make pull-ministral antes la primera vez
	$(COMPOSE_MINISTRAL) up -d

down-ministral:  ## Detiene el perfil Ministral (mismos -f que up-ministral)
	$(COMPOSE_MINISTRAL) down

up-qwen35:  ## Levanta el perfil experimental Qwen3.5 4B (LLM via Ollama, fix de thinking integrado); corre make pull-qwen35 antes la primera vez
	$(COMPOSE_QWEN35) up -d

down-qwen35:  ## Detiene el perfil Qwen3.5 (mismos -f que up-qwen35)
	$(COMPOSE_QWEN35) down

up-nemotron:  ## Levanta el perfil experimental Nemotron-mini 4B (LLM via Ollama, sin thinking); corre make pull-nemotron antes la primera vez
	$(COMPOSE_NEMOTRON) up -d

down-nemotron:  ## Detiene el perfil Nemotron-mini (mismos -f que up-nemotron)
	$(COMPOSE_NEMOTRON) down

up-granite41:  ## Levanta el perfil experimental Granite 4.1 3B; corre make pull-granite41 antes la primera vez
	$(COMPOSE_GRANITE41) up -d

down-granite41:  ## Detiene el perfil Granite 4.1 (mismos -f que up-granite41)
	$(COMPOSE_GRANITE41) down

up-phi4mini:  ## Levanta el perfil experimental Phi-4 Mini 3.8B; corre make pull-phi4mini antes la primera vez
	$(COMPOSE_PHI4MINI) up -d

down-phi4mini:  ## Detiene el perfil Phi-4 Mini (mismos -f que up-phi4mini)
	$(COMPOSE_PHI4MINI) down

up-qwen25:  ## Levanta el perfil experimental Qwen2.5 3B; corre make pull-qwen25 antes la primera vez
	$(COMPOSE_QWEN25) up -d

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

health:  ## Reporte de salud detallado: db, ollama y modelos faltantes
	@curl -fsS http://localhost:$${KB_PORT:-8080}/actuator/health | python -m json.tool 2>/dev/null \
	  || curl -fsS http://localhost:$${KB_PORT:-8080}/actuator/health

## ---------------------------------------------------------------- modelos

pull-models:  ## Descarga LLM, embeddings y reranker a KB_DATA_DIR (~5.5 GB, una sola vez)
	$(COMPOSE_ACTIVO) exec ollama ollama pull $(LLM)
	$(COMPOSE_ACTIVO) exec ollama ollama pull $(EMBEDDINGS)
	$(MAKE) pull-reranker
	@echo ""
	@echo "Listo. Si vas a usar la GPU, corre tambien: make pin-embeddings-cpu"

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
	@# La T600 tiene 4 GB: gemma3:4b mas bge-m3 no caben juntos con holgura.
	@# Los embeddings van a CPU, donde el AVX-512 de este equipo los hace baratos,
	@# y la GPU queda integra para la sintesis, que es la etapa que se espera.
	$(COMPOSE_ACTIVO) exec -T ollama sh -c 'printf "FROM $(EMBEDDINGS)\nPARAMETER num_gpu 0\n" > /tmp/Modelfile.cpu && ollama create $(EMBEDDINGS)-cpu -f /tmp/Modelfile.cpu'
	@echo ""
	@echo "Ahora pon en tu .env:  KB_EMBEDDINGS_MODELO=$(EMBEDDINGS)-cpu"

seed: ingest  ## Alias de ingest: nombre usado en la seccion Verificacion del plan (corpus de ejemplo)

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

secrets:  ## Scan the working tree for committed secrets
	gitleaks detect --no-banner --redact

check: lint build test  ## Single local confidence signal
	@echo "OK -- the repo is green"

ci: lint build test secrets  ## What the CI pipeline runs
	@echo "OK -- CI gates passed"

hooks:  ## Install git hooks (Lefthook)
	lefthook install
