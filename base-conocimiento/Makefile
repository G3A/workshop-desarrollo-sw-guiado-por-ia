.DEFAULT_GOAL := help
.PHONY: help up gpu-up up-bonsai down-bonsai up-ministral down-ministral up-qwen35 down-qwen35 up-nemotron down-nemotron up-granite41 down-granite41 up-phi4mini down-phi4mini up-qwen25 down-qwen25 down restart logs ps build test verify pull-models pull-reranker pull-bonsai-gguf pull-ministral pull-qwen35 pull-nemotron pull-granite41 pull-phi4mini pull-qwen25 pin-embeddings-cpu seed ingest ingest-repos ingest-teams ingest-azdo psql health clean

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

up:  ## Levanta los 4 servicios; usa la GPU NVIDIA automaticamente si el host tiene una
	$(COMPOSE_ACTIVO) up -d --build
	@echo "Perfil activo: $(if $(HAY_GPU),GPU (compose.gpu.yml) -- Ollama con VRAM reservada,CPU (compose.yml) -- no se detecto GPU NVIDIA)"

gpu-up:  ## Fuerza el perfil GPU aunque la deteccion automatica no encuentre nvidia-smi
	$(COMPOSE_GPU) up -d --build

up-bonsai:  ## Levanta el perfil Bonsai-8B (LLM 1-bit via llama-server); requiere GPU NVIDIA y el GGUF de pull-bonsai-gguf
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
