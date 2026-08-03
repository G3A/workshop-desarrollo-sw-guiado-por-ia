.DEFAULT_GOAL := help
.PHONY: help up gpu-up down restart logs ps build test verify pull-models pull-reranker pin-embeddings-cpu seed ingest ingest-repos ingest-teams ingest-azdo psql health clean

COMPOSE     := docker compose
COMPOSE_GPU := docker compose -f compose.yml -f compose.gpu.yml
LLM         ?= gemma3:4b
EMBEDDINGS  ?= bge-m3
KB_DATA_DIR ?= ./.data
KB_PORT     ?= 8080

# Deteccion automatica de GPU NVIDIA: nvidia-smi solo existe y responde si hay
# tarjeta y el driver esta instalado (con nvidia-container-toolkit, Docker ya
# puede reservarla). Si el resultado es "1", todos los targets de infra usan
# compose.gpu.yml sin que haga falta pedirlo a mano.
HAY_GPU := $(shell command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1 && echo 1)

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

up:  ## Levanta los 3 servicios; usa la GPU NVIDIA automaticamente si el host tiene una
	$(COMPOSE_ACTIVO) up -d --build
	@echo "Perfil activo: $(if $(HAY_GPU),GPU (compose.gpu.yml) -- Ollama con VRAM reservada,CPU (compose.yml) -- no se detecto GPU NVIDIA)"

gpu-up:  ## Fuerza el perfil GPU aunque la deteccion automatica no encuentre nvidia-smi
	$(COMPOSE_GPU) up -d --build

down:  ## Detiene los servicios (los datos en KB_DATA_DIR sobreviven)
	$(COMPOSE_ACTIVO) down

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

pin-embeddings-cpu:  ## Crea bge-m3-cpu, fijado a CPU, para dejarle toda la VRAM al LLM
	@# La T600 tiene 4 GB: gemma3:4b mas bge-m3 no caben juntos con holgura.
	@# Los embeddings van a CPU, donde el AVX-512 de este equipo los hace baratos,
	@# y la GPU queda integra para la sintesis, que es la etapa que se espera.
	$(COMPOSE_ACTIVO) exec -T ollama sh -c 'printf "FROM $(EMBEDDINGS)\nPARAMETER num_gpu 0\n" > /tmp/Modelfile.cpu && ollama create $(EMBEDDINGS)-cpu -f /tmp/Modelfile.cpu'
	@echo ""
	@echo "Ahora pon en tu .env:  KB_EMBEDDINGS_MODELO=$(EMBEDDINGS)-cpu"

seed: ingest  ## Alias de ingest: nombre usado en la seccion Verificacion del plan (corpus de ejemplo)

ingest:  ## Ingiere ./corpus: documentos nuevos o cambiados quedan troceados y encolados para embeber
	@curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/local-docs | python -m json.tool 2>/dev/null \
	  || curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/local-docs

ingest-repos:  ## Ingiere ./repos (F6): repos Git locales nuevos o cambiados
	@curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/repos-locales | python -m json.tool 2>/dev/null \
	  || curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/repos-locales

ingest-teams:  ## Ingiere el canal de Teams configurado (F6, no-op si KB_GRAPH_HABILITADO=false)
	@curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/teams-graph | python -m json.tool 2>/dev/null \
	  || curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/teams-graph

ingest-azdo:  ## Ingiere work items y wiki de Azure DevOps (F6, no-op si KB_AZDO_HABILITADO=false)
	@curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/azure-devops | python -m json.tool 2>/dev/null \
	  || curl -fsS -X POST http://localhost:$(KB_PORT)/api/ingest/azure-devops

## ---------------------------------------------------------------- desarrollo

build:  ## Compila el jar sin correr pruebas
	./mvnw -B clean package -DskipTests

test:  ## Corre las pruebas, incluidos los gates de arquitectura
	./mvnw -B test

verify:  ## Build completo con todos los gates
	./mvnw -B clean verify

psql:  ## Abre una sesion psql contra la base
	$(COMPOSE_ACTIVO) exec db psql -U $${POSTGRES_USER:-kb} -d $${POSTGRES_DB:-baseconocimiento}

clean:  ## Borra artefactos de compilacion (NO toca los modelos ni la base)
	./mvnw -B clean
