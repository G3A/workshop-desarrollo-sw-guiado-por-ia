#!/bin/sh
# CMD en forma exec no interpola variables de entorno -- este script existe
# solo para poder parametrizar contexto/capas de GPU desde compose.bonsai.yml.
set -e

exec ./llama-server \
  --model "${BONSAI_MODELO_RUTA:-/models/bonsai/Bonsai-8B-Q1_0.gguf}" \
  --host 0.0.0.0 \
  --port 8080 \
  --ctx-size "${BONSAI_CTX_SIZE:-4096}" \
  --n-gpu-layers "${BONSAI_GPU_LAYERS:-99}"
