# ADR-0015: Granite 4.1 3B en Q3_K_M es el LLM por defecto

## Estado

Aceptado (2026-10-30). Desde ADR-0017 el modelo lo sirve llama.cpp, no Ollama.

## Contexto

`compose.yml`, el perfil que levanta `make up`, seguía con `gemma3:4b` como LLM y como modelo del
destilador de Teams, aunque [la investigación](../investigacion-vram-y-modelo-llm.md) ya lo había
dejado atrás: en la T600 de 4 GB del taller no entra entero en la VRAM (40 % GPU / 60 % CPU) y
sobrecita fuentes (sesión 3). La investigación señaló como configuración de producción a Ministral
3 3B (`compose.ministral.yml`, 85,3 % en el piloto de 100 preguntas, sesión 17), pero tampoco
entra entero (medido ahora: 13 de 27 capas en GPU) y tarda 192–428 s por pregunta. En las sesiones
25 y 26, `granite4.1:3b` (Q4_K_M) fue el único candidato con 10 de 10 respuestas sustantivas en el
smoke10, en texto libre y en salida estructurada.

Se buscó una cuantización de Granite 4.1 3B que entrara entera en la GPU. Candidatas, todas del
repo oficial de IBM (`ibm-granite/granite-4.1-3b-GGUF`, Apache-2.0):

- **Q2_K** (1,3 GB): entra entera, pero no sirve. Sin contexto responde con errores y un español
  roto; con el RAG, en la primera pregunta del smoke10 repitió la misma oración 34 veces hasta
  agotar el límite de tokens (320 s).
- **Q3_K_M** (1,6 GB en disco, 2,1 GB cargado): entra entera (100 % GPU) y responde bien.
- **Q4_K_M** (2,0 GB): la que midió la investigación; 81 % GPU / 19 % CPU.

## Decisión

El LLM por defecto (`KB_LLM_MODELO`, y `KB_TEAMS_LLM_MODELO` para el destilador) es
`hf.co/ibm-granite/granite-4.1-3b-GGUF:Q3_K_M`, servido por Ollama, con los parámetros del perfil
`compose.granite41.yml`: techo de confianza 8,0, 6 fragmentos de contexto, top 6 en el rerank,
tope 20 por documento y 450 s de timeout. `make pull-models` lo descarga.

**Medición** (smoke10 de `eval-100-preguntas`, RAG completo, base con `jls25.pdf` extraído por
OpenDataLoader, ADR-0014): 9 de 10 correctas, en español correcto y citando la fuente, sin
bucles. 42–53 s por pregunta cuando la primera búsqueda alcanza; 3–8 min cuando hay que reformular
(dos rondas de búsqueda más la síntesis). La única falla (#9) fue un rechazo después de reformular:
la reformulación salió en español para buscar en un documento en inglés.

## Consecuencias

- **A favor**: el modelo entra entero en 4 GB de VRAM, con lugar para el resto; descarga de
  1,6 GB en vez de 3,3 GB. Un solo LLM para todo el pipeline y el destilador.
- **En contra**: 10 preguntas son una señal, no una cifra: el piloto de 100 sigue pendiente y,
  hasta tenerlo, el 85,3 % de Ministral es el único número sobre las 100. El umbral de VRAM de
  `make gpu-check` para mandar los embeddings a CPU se calibró con `gemma3:4b` y no se remidió.
- **Encontrado**: el `Reformulador` a veces reformula en el idioma de la pregunta y no en el de
  las fuentes (#9), y a veces agrega el nombre del archivo a la búsqueda (`… jls25`, #8). No
  depende del modelo de síntesis; queda para revisar aparte.
