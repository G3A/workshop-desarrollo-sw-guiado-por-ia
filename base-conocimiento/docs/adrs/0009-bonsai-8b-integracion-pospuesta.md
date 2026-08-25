# ADR-0009: Bonsai 8B (PrismML) como modelo de síntesis — integración pospuesta

## Estado

**Implementada** (actualizado en la sesión 8, desactualizado desde la sesión 6 sin corregir hasta
ahora). El título y buena parte del cuerpo de este ADR describen la decisión original de posponer;
esa decisión se revirtió en la práctica entre las sesiones 6 y 8 sin que este encabezado se
actualizara. Estado real de `main`/este worktree:

- `spring.ai.openai.chat.options.model` (default `Bonsai-8B-Q1_0.gguf`) es el modelo de
  `PlanificadorOpenAi`, `VerificadorGroundingOpenAi` y `SintetizadorOpenAi` — los tres roles
  principales del pipeline — sirviéndose desde `llama-server` (`Dockerfile.bonsai`,
  `compose.bonsai.yml`).
- `KB_LLM_MODELO` ya no es el selector vigente de ese modelo (ese nombre quedó del esquema
  anterior, ver `application.yml`); el default es Bonsai, no `gemma3:4b`.
- Ollama sigue activo solo para `bge-m3` (embeddings) y para el Destilador de Teams (F6,
  `DestiladorOllama`), que nunca se evaluó con Bonsai y se dejó fuera de este ADR a propósito.
- Quedan ajustes sin resolver documentados en la actualización de la sesión 8 más abajo (contexto
  acotado a `KB_MAX_FRAGMENTOS_CONTEXTO=2` en el perfil Bonsai, calidad de citación con
  `repeat_penalty` todavía por debajo de lo medido en el hallazgo 12).

El resto de este documento (Contexto, Decisión, Consecuencias) queda como registro histórico de
por qué se pospuso originalmente y qué cambió sesión a sesión hasta revertir esa decisión — no se
reescribe para no perder ese razonamiento.

## Contexto

`compose.gpu.yml` asume que la T600 de referencia (4096 MiB nominales) le alcanza a `gemma3:4b`
para el modelo de síntesis. La investigación completa está en
[`docs/investigacion-vram-y-modelo-llm.md`](../investigacion-vram-y-modelo-llm.md) (siete
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
   **Actualización (sesión 6, ver hallazgos 17-18 de la investigación): este punto ya se prototipó y
   funciona.** `Dockerfile.bonsai` + `compose.bonsai.yml`, comiteados en la rama aislada
   `worktree-experimento+bonsai-llama-server` (no en `main`), levantan el contenedor real contra la
   GPU real: 1753 MiB de VRAM y 6.9 tok/s, consistente con lo medido en la sesión 5. Dos bugs de
   build nuevos que la compilación manual de la sesión 5 no atravesaba (la imagen `*-devel` no trae
   el driver CUDA real en build-time, y la imagen `*-runtime` no trae `libgomp1`) quedaron resueltos
   ahí. Sigue siendo la pieza de mayor incertidumbre técnica original, pero ya no es incertidumbre:
   es trabajo hecho y validado, a falta de decidir si se integra contra `main`.
2. Decidir qué pasa con los embeddings (`bge-m3`): hoy corren en el mismo Ollama, fijados a CPU
   (`bge-m3-cpu`). O se quedan en un Ollama que convive con el nuevo `llama-server` (dos servidores
   de inferencia en vez de uno), o se buscan otra vía. **Sin resolver todavía** — el prototipo de la
   sesión 6 no tocó esto.
3. Cambiar el cliente de Spring AI en `llm/` de `OllamaChatModel` a `OpenAiChatModel` apuntando al
   `llama-server` local, en los tres componentes (`SintetizadorOllama`, `VerificadorGroundingOllama`,
   `PlanificadorOllama` — probablemente renombrados, dejarían de ser "Ollama").
   **Actualización (sesión 6, hallazgo 21): investigado pero no implementado.** El wiring es viable
   y acotado — cambiar la dependencia Maven, `OllamaChatOptions` por `OpenAiChatOptions`, y eliminar
   `enableThinking()`/`disableThinking()` (Bonsai no tiene modo thinking). El único parámetro que
   necesita `extraBody(Map.of(...))` en vez de un campo nativo de `OpenAiChatOptions` es
   `repeat_penalty` (ver punto 4 y hallazgo 20) — no es parte de la API oficial de OpenAI.
4. Generar a mano una gramática GBNF por cada tipo de salida estructurada (`PlanDeHerramientas`,
   `Veredicto`) para reemplazar `spec.useProviderStructuredOutput()`, porque el flag
   `-j`/`--json-schema` de este fork falla con `Failed to initialize samplers: std::exception`
   (hallazgo 14) — un bug de esta build específica, no del modelo.
   **Actualización (sesión 6, hallazgo 19): probablemente no hace falta.** Probado contra
   `POST /v1/chat/completions` con `response_format: json_schema` (el mecanismo real de
   `useProviderStructuredOutput()` contra un proveedor OpenAI) en vez del flag `-j`/`--json-schema`
   del CLI: no crasheó, acertó los dos veredictos canónicos del ADR-0008, y el plan de herramientas
   salió más preciso que con la gramática GBNF de la sesión 5. El bug del hallazgo 14 parece ser
   del CLI, no de la API runtime — pero es una inferencia de una sola sesión, conviene re-confirmar
   si se retoma.
   **Hallazgo nuevo de la sesión 6 (20), no anticipado por ningún punto de esta lista**: sin
   `repeat_penalty` explícito, la síntesis por streaming repitió la respuesta completa dos veces —
   un modo de falla que ni la sesión 5 (`llama-cli`) ni ninguna sesión anterior había visto. Se
   corrige agregando `repeat_penalty: 1.1` a la request, pero al hacerlo la citación salió peor que
   la medida en el hallazgo 12 (citó un fragmento irrelevante, puso una cita antes de la afirmación
   en vez de después) — la mejor citación de la investigación no se reprodujo automáticamente al
   pasar de `llama-cli` a la API HTTP real. Queda como tarea de ajuste de sampling y re-validación,
   no identificada hasta esta sesión.

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
     **Parcialmente cubierta (sesión 7)**: la cuantización Q1_0, la que ya usa el
     `Bonsai-8B-Q1_0.gguf` de este proyecto, se fusionó al `llama.cpp` mainline
     (`ggml-org/llama.cpp#21417`) — pero solo para ese modelo puntual, no para el soporte de
     cuantización en general: la variante ternaria Q2_0 evaluada en esa sesión sigue sin CUDA en
     mainline (`ggml-org/llama.cpp#25707`, sin mergear). No hay soporte nativo en Ollama todavía.
  2. Se corrige el bug de `-j`/`--json-schema` (hallazgo 14) río arriba, evitando mantener
     gramáticas GBNF escritas a mano. **Parcialmente cubierta (sesión 6, hallazgo 19)**: el bug no
     se reprodujo contra la API runtime de `llama-server` (solo contra el flag del CLI), así que las
     gramáticas GBNF probablemente no hacen falta ya — pero eso no cierra la condición del todo,
     porque la sesión 6 sumó un problema nuevo en la misma zona (hallazgo 20: sampling/citación) que
     todavía necesita ajuste y re-validación antes de confiar en la calidad medida en el hallazgo 12.
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
  **Ya hecho (sesión 6)**: el prototipo vive en `worktree-experimento+bonsai-llama-server`
  (`Dockerfile.bonsai`, `compose.bonsai.yml`, `entrypoint-bonsai.sh`), validado contra la GPU real.
  Si se retoma la integración de verdad, el siguiente paso ya no es el `Dockerfile`, sino el ajuste
  de sampling y la re-validación de calidad de síntesis que sacó a la luz el hallazgo 20 — antes de
  tocar el cliente de Spring AI en `main` (punto 3).

## Actualización (sesión 7): se buscó un reemplazo superior a Bonsai-8B — no se encontró uno

Disparada por la pregunta directa "¿hay un modelo mejor que el Bonsai ya integrado?". Investigación
completa en
[`docs/investigacion-vram-y-modelo-llm.md`, sesión 7](../investigacion-vram-y-modelo-llm.md)
(hallazgos 22-29). Resumen:

- **`Ternary-Bonsai-8B`** (mismo lab, cuantización ternaria 1.58 bit, +5 puntos de score agregado
  contra el `Bonsai-8B` 1-bit actual) parecía el sucesor natural, pero resultó ser una cuantización de
  **Qwen3-8B** — no un entrenamiento nativo como el 1-bit original — con modo "thinking" activo por
  defecto (`thinking=1` en el log de `llama-server`). Probado empíricamente contra el pipeline real
  (mismo protocolo de las sesiones 5-6): el thinking no llegó a filtrarse en la salida JSON forzada
  (`VerificadorGrounding` acertó los dos veredictos canónicos), pero el candidato **retrocede** en dos
  ejes ya validados del Bonsai actual — peor elección de herramientas en `Planificador`, y peor
  citación en la síntesis de la pregunta relevante (cita hasta el fragmento irrelevante, pega texto
  casi crudo) — y deja mucha menos VRAM libre (574 MiB contra los ~2.3 GB del actual). Sí mejora en un
  eje que ningún candidato anterior había resuelto: es el primero de trece que no alucina en la
  pregunta de control fuera de dominio. Balance: un trade-off distinto, no una mejora neta — **no
  justifica reabrir la integración**.
- **`Bonsai-27B`** (basado en Qwen3.6-27B, la variante 1-bit pesa 3.9 GB) se descartó sin probar: no
  entra en los ~3.3-3.9 GB libres reales de la T600.
- **Hallazgo colateral que sí es accionable, sin cambiar de modelo**: la cuantización Q1_0 (la que ya
  usa este proyecto) se fusionó al `llama.cpp` mainline (confirmado en
  [`ggml-org/llama.cpp#21417`](https://github.com/ggml-org/llama.cpp/discussions/21417)) — builds
  recientes de la rama principal la corren sin el fork de PrismML. Esto satisface **parcialmente** la
  condición de reapertura #1 de más abajo (solo para el modelo ya integrado, no para candidatos
  nuevos: la ternaria de arriba sigue necesitando el fork, `ggml-org/llama.cpp#25707` sigue sin
  mergear). No se reconstruyó `Dockerfile.bonsai` contra el mainline en esta sesión — queda como
  mejora de bajo riesgo pendiente, independiente de la decisión de este ADR.
- **No evaluados empíricamente** (solo por research): `Ministral 3B Instruct` y `Hermes 2 Pro -
  Mistral 7B`, ambos Apache 2.0 con soporte GGUF nativo sin fork de terceros — a diferencia de
  cualquier variante de Bonsai, eliminarían por completo el punto 1 de la lista de arquitectura de
  este ADR. Son la vía más prometedora para una sesión futura si en algún momento se prioriza bajar el
  riesgo de arquitectura por encima de la calidad de citación medida hoy.

**Esta sesión no cambia la decisión de este ADR** (sigue pospuesta) — la refuerza con evidencia nueva:
de los trece candidatos probados hasta ahora en total, ninguno supera al `Bonsai-8B` 1-bit ya
integrado en el balance completo de VRAM, `Planificador` y citación.

## Actualización (sesión 8): tres ajustes en vivo para que el pipeline complete sin caerse

Disparada por una pregunta real del usuario ("¿cuáles son los tipos primitivos en Java?")
respondiendo siempre con el mensaje genérico de "sin información relevante". Depurado en vivo
contra el stack real (`kb-api` + `llama-server` + `Bonsai-8B` en una T600). El encabezado
**Estado** de este ADR sigue diciendo "Pospuesta. No implementada" pese a que el pipeline ya corre
contra Bonsai (`compose.bonsai.yml`, `PlanificadorOpenAi`/`SintetizadorOpenAi`/
`VerificadorGroundingOpenAi`) — desactualizado desde la integración real, no corregido en esta
sesión por no ser el foco del hallazgo.

Tres problemas distintos, en cadena, no uno solo:

1. **Límite de contexto de `llama-server` (`BONSAI_CTX_SIZE=4096`) superado.** El default de
   `kb.orquestacion.max-fragmentos-contexto` (10) arma un prompt de ~5070 tokens con secciones
   largas de `jls25.pdf` — 400 `exceeds the available context size`. Bajar de 10 a 6 fragmentos
   apenas movió el total (5070 → 5068): `Orquestador.construirContexto()` expande cada fragmento
   con sus 2 chunks vecinos (`ContextoRepositorio.vecinos`), y el tamaño lo domina esa expansión,
   no la cantidad de fragmentos. Con 4 fragmentos: 4339 tokens, todavía sobre el límite (dejando
   margen para los 512 tokens de salida del sintetizador). Con 2: entra. Subir `BONSAI_CTX_SIZE`
   en vez de bajar fragmentos no es gratis: solo ~1.6 GB libres de los 4 GB de VRAM de la T600 en
   el momento de la prueba. `KB_MAX_FRAGMENTOS_CONTEXTO=2` queda como default del perfil Bonsai
   (`compose.bonsai.yml`), a costa de menos citas por respuesta.
2. **El planificador enrutaba mal preguntas conceptuales del lenguaje.** Para "cuáles son los
   tipos primitivos en Java", Bonsai eligió `search_code` (razón: *"pregunta sobre implementación
   específica"*) — busca con `ripgrep` sobre `vault/repos`, vacío en este entorno, cero resultados.
   El contenido sí estaba ingerido (confirmado con `/api/search` directo). El prompt de
   `PlanificadorOpenAi` ya distinguía "requisitos de hardware/despliegue" de "código", pero no
   "conceptos del lenguaje" (tipos, sintaxis, palabras reservadas) de "cómo está implementado X
   en este repo". Se agregó un ejemplo explícito con esa distinción — corrigió el ruteo en la
   siguiente corrida (`search_docs`+`search_unified`, razón: *"pregunta conceptual, no de
   implementación"*).
3. **`KB_LLM_TIMEOUT` (180s) insuficiente para una corrida completa.** Con el planificador ya
   enrutando bien, el pipeline ejecuta varias llamadas secuenciales a Bonsai (planificador +
   herramientas + posible verificador de grounding + síntesis) que a ~1-3.5 tok/s en esta GPU
   pueden superar los 180s en total — medido en vivo (`java.io.InterruptedIOException: timeout`
   del cliente OpenAI). Subido a `KB_LLM_TIMEOUT=300s` en el perfil Bonsai.

Con los tres ajustes juntos, la pregunta original respondió correctamente en 34 s: *"Los tipos
primitivos en Java son: byte, short, int, long, char, float y double [1]"*, citando `jls25.pdf`.

**Hallazgo aparte, no resuelto**: en una corrida anterior (pregunta de despliegue, antes del ajuste
de contexto) la síntesis filtró la palabra "PEGADO" —tomada literalmente de su propio prompt de
sistema— a la respuesta final, y citó un fragmento irrelevante de `jls25.pdf`. Coincide con el
hallazgo ya documentado en `SintetizadorOpenAi.java` ("la citación todavía midió peor... sigue
pendiente más ajuste y re-validación") y con el hallazgo 12/de citación de sesiones anteriores de
este ADR — no es nuevo, sigue sin resolverse.
