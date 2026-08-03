# ADR-0009: Bonsai 8B (PrismML) como modelo de síntesis — integración pospuesta

## Estado

Pospuesta. No implementada. `KB_LLM_MODELO` sigue apuntando a un modelo servido por Ollama
(`gemma3:4b` en producción/demo; ver ADR-0008).

## Contexto

`compose.gpu.yml` asume que la T600 de referencia (4096 MiB nominales) le alcanza a `gemma3:4b`
para el modelo de síntesis. La investigación completa está en
[`docs/investigacion-vram-y-modelo-llm.md`](../investigacion-vram-y-modelo-llm.md) (cinco
sesiones); acá solo el resumen que sostiene esta decisión.

**El problema de fondo (sesiones 1-4).** Con `nvidia-smi` y los logs de Ollama se midió que solo
~3297 MiB están libres de verdad al cargar el modelo (el resto lo usa el propio escritorio de
Windows). `gemma3:4b` (Q4_K_M, 4.3B parámetros) no entra completo ni estando solo: queda en 60%
GPU / 40% CPU. Se probaron once alternativas más (`qwen3:4b`, `granite4.1:3b`,
`gemma3:4b-it-qat`, `tomng/nanbeige4.1:3b-q4_K_M`, `phi4-mini:3.8b`, `openbmb/minicpm5`,
`qwen2.5:3b`, `llama3.2:3b`, `gemma2:2b`, más reducir `num_ctx` a la mitad) y ninguna combina
"entra 100% en VRAM" con "pasa la barra de calidad de síntesis que ya tiene `gemma3:4b`" — o entran
completas pero pegan texto crudo / alucinan en preguntas fuera de dominio, o tienen buena calidad
pero no entran completas, o su modo de "thinking" nativo no converge en las llamadas de salida JSON
forzada del pipeline (`Planificador`, `VerificadorGrounding`).

**`Bonsai 8B` (sesión 5) es, con diferencia, el mejor candidato encontrado.** Entrenado
*nativamente* en 1-bit (no cuantización post-entrenamiento de un modelo en punto flotante), 8.2 mil
millones de parámetros en 1.15 GB de disco, Apache 2.0. Medido contra el mismo prompt de sistema y
el mismo contexto que usa este pipeline (`llama-cli` suelto, fuera de Ollama — ver más abajo):

| Eje | Resultado | Hallazgo |
|---|---|---|
| VRAM | **1.76 GB reales, 100% GPU** — menos de la mitad del presupuesto de la T600 | 11 |
| Síntesis, pregunta relevante | La mejor citación medida en toda la investigación, mejor que `gemma3:4b` | 12 |
| Síntesis, pregunta de control | Alucina igual que los otros once candidatos (con la puerta de relevancia apagada) | 13 |
| `VerificadorGrounding` (JSON forzado) | Acertó los dos veredictos canónicos del ADR-0008 | 14 |
| `Planificador` (JSON forzado) | Eligió bien en los dos casos probados, algo menos preciso que `gemma3:4b`/`qwen2.5:3b` | 15 |
| Puerta de relevancia end-to-end | Validada con `gemma3:4b` en el pipeline real (no con Bonsai — ver más abajo); por composición con el hallazgo 14, hay confianza razonable de que se comportaría igual | 16 |

**El obstáculo no es de calidad, es de arquitectura.** El GGUF de Bonsai usa un tipo de
cuantización propio (`Q1_0 (g128)`) que solo corre en un fork propio de llama.cpp
([`PrismML-Eng/llama.cpp`](https://github.com/PrismML-Eng/llama.cpp)) — el `ollama/ollama:latest`
que usa `compose.yml` no lo soporta. Todas las pruebas de la sesión 5 se hicieron con ese fork
compilado a mano (`GGML_CUDA=ON`, arquitectura Turing) y `llama-cli` suelto, pegando a mano los
mismos prompts que usan `SintetizadorOllama`, `VerificadorGroundingOllama` y `PlanificadorOllama` —
nunca contra el pipeline real. Contra el pipeline real solo se validó el mecanismo de la puerta de
relevancia (hallazgo 16), y ahí con `gemma3:4b`, no con Bonsai, precisamente porque Bonsai no puede
conectarse hoy.

Para correrlo de verdad en este proyecto haría falta:

1. Reemplazar (o sumar) el servicio `ollama` de `compose.yml` por uno que sirva el fork de
   PrismML compilado con CUDA (`llama-server`, que expone una API compatible con OpenAI) —
   `Dockerfile` nuevo con una etapa de build sobre una imagen `nvidia/cuda:*-devel` (~7 GB, contra
   los `mvnw`/imagen oficial de Maven que usa el build actual).
2. Decidir qué pasa con los embeddings (`bge-m3`): hoy corren en el mismo Ollama, fijados a CPU
   (`bge-m3-cpu`). O se quedan en un Ollama que convive con el nuevo `llama-server` (dos servidores
   de inferencia en vez de uno), o se buscan otra vía.
3. Cambiar el cliente de Spring AI en `llm/` de `OllamaChatModel` a `OpenAiChatModel` apuntando al
   `llama-server` local, en los tres componentes (`SintetizadorOllama`, `VerificadorGroundingOllama`,
   `PlanificadorOllama` — probablemente renombrados, dejarían de ser "Ollama").
4. Generar a mano una gramática GBNF por cada tipo de salida estructurada (`PlanDeHerramientas`,
   `Veredicto`) para reemplazar `spec.useProviderStructuredOutput()`, porque el flag
   `-j`/`--json-schema` de este fork falla con `Failed to initialize samplers: std::exception`
   (hallazgo 14) — un bug de esta build específica, no del modelo.

## Decisión

**Se pospone la integración.** `KB_LLM_MODELO` sigue sin cambios (ver ADR-0008 para la
recomendación de producción/demo vigente, `gemma3:4b`, con su defecto de sobre-citación conocido y
sin resolver). No se toca `compose.yml`, `Dockerfile` ni el cliente de Spring AI.

La razón no es la calidad de Bonsai — de los doce candidatos probados en cuatro sesiones y medio,
es el único que resuelve el problema de VRAM sin sacrificar calidad medible. La razón es que el
costo y el riesgo de integrarlo hoy superan ese beneficio, en este proyecto puntual:

- **Fork de un solo proveedor, recién salido de stealth** (anuncio de PrismML, marzo 2026): sin
  comunidad, sin garantía de que siga sincronizado con las actualizaciones de seguridad o features
  del `llama.cpp` upstream. Ya se le encontró un bug real en la primera sesión de pruebas
  (`-j`/`--json-schema`, hallazgo 14) — evidencia de que es software joven, no una curiosidad
  aislada.
- **Aumenta la complejidad de build de un proyecto que se apoya en esa simplicidad como parte de su
  propuesta de valor.** El README es explícito: *"Java y Maven no hacen falta para ejecutarlo: el
  build ocurre dentro de Docker"*, con imágenes oficiales (`ollama/ollama`, `pgvector/pgvector`).
  Compilar un fork de C++/CUDA de terceros dentro del build agrega una etapa de varios minutos sobre
  una imagen `nvidia/cuda:*-devel` de ~7 GB, y con eso un punto de falla nuevo (¿compila igual en la
  próxima versión del fork? ¿en otra arquitectura de GPU?) que no existe hoy con `ollama pull`.
- **La ganancia de velocidad prometida no se sostiene en esta GPU.** PrismML mide "8x más rápido"
  contra RTX 4090 (368 tok/s) — Turing (T600, 2018) no tiene el mismo camino de kernels optimizados
  para operaciones de bits bajos, y la medición real en esta máquina fue 5.8-6.1 tok/s (hallazgo 11).
  La ganancia real y medida es de VRAM, no de velocidad — un beneficio más acotado que el que
  sugiere el marketing del proyecto.
- **Nunca se probó el pipeline completo con Bonsai en los tres roles a la vez, solo aislados.** El
  hallazgo 16 (la puerta de relevancia funciona end-to-end) se validó con `gemma3:4b`, no con
  Bonsai — la confianza en que Bonsai se comportaría igual es una inferencia por composición de
  pruebas separadas (hallazgo 14 + hallazgo 16), razonable pero no una prueba directa. El hallazgo 7
  de la propia investigación (sesión 2) ya advirtió que el comportamiento aislado de un modelo no
  siempre predice su comportamiento embebido en el pipeline real.
- **No hay presión real que lo justifique todavía.** `gemma3:4b` funciona hoy (con el defecto de
  cita conocido, no uno que rompa el contenido) y la T600 sostiene el taller en su forma actual, aun
  sin usar el 100% de su VRAM.

## Consecuencias

- **A favor de posponer**: el proyecto se mantiene reproducible con `docker compose up` e imágenes
  oficiales, sin una segunda cadena de build de C++/CUDA que mantener ni un fork de un solo
  proveedor del que depender. El riesgo se documenta y queda disponible para revisar, no se pierde.
- **En contra — el costo de esperar**: se sigue corriendo con `gemma3:4b` al 60%/40% CPU/GPU (más
  lento de lo que sería con margen de VRAM sobrado) y con su defecto de sobre-citación sin resolver
  (ADR-0008, hallazgo 6), pudiendo tener disponible un modelo que mide mejor en ambos ejes.
- **Condiciones concretas para reabrir esta decisión**: cualquiera de estas alcanza para volver a
  evaluar, no hace falta que se cumplan todas —
  1. El fork de PrismML (o el soporte de este esquema de cuantización) se integra a la rama
     principal de `llama.cpp`, y de ahí a Ollama — desaparece el punto 1 de la lista de trabajo de
     arquitectura de arriba, y con él la mayor parte del riesgo de mantenimiento.
  2. Se corrige el bug de `-j`/`--json-schema` (hallazgo 14) río arriba, evitando mantener
     gramáticas GBNF escritas a mano.
  3. La VRAM de la T600 se vuelve un problema real y no solo un margen ajustado — por ejemplo, si
     se necesita correr `bge-m3` en GPU a la vez que el LLM, o un contexto mayor a 4096.
  4. Aparece una necesidad real de arreglar el defecto de sobre-citación de `gemma3:4b`
     (ADR-0008) y las dos vías de prompt-tuning ya intentadas ahí siguen sin funcionar — la mejor
     citación medida de Bonsai (hallazgo 12) pasaría de "agradable de tener" a la razón principal
     para migrar.
- Si se retoma, empezar por el punto 1 de la lista de trabajo de arquitectura (el `Dockerfile` y
  `compose.yml` de `llama-server`) en una rama o *worktree* aislado, no contra `main` directo — es
  la pieza de mayor incertidumbre técnica (multi-stage build con CUDA, tamaño de imagen final) y
  conviene validarla antes de tocar el cliente de Spring AI o los prompts.
