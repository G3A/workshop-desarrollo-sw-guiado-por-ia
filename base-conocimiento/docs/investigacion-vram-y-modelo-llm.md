# Investigación: VRAM real disponible y alternativas a `gemma3:4b`

Notas de una sesión de diagnóstico sobre el perfil GPU (`compose.gpu.yml`), disparada por la
pregunta "¿el modelo actual cabe en la VRAM de esta máquina?". No es un ADR: no hay una decisión
final tomada, solo evidencia recolectada y el estado en que quedó el entorno. Ver
[`docs/adrs/0008-umbral-de-relevancia-antes-de-sintesis.md`](adrs/0008-umbral-de-relevancia-antes-de-sintesis.md)
para la evaluación previa de `granite4.1:3b` en la etapa de síntesis, que ya lo había descartado
antes de esta sesión.

## Hallazgo 1: la GPU de referencia no tiene 4 GB libres de verdad

`compose.gpu.yml` asume que la T600 (4096 MiB nominales) le da a Ollama margen suficiente para
`gemma3:4b` solo. En la práctica, con `nvidia-smi` y los logs de Ollama (`common_memory_breakdown_print`,
`common_params_fit_impl`) se vio que solo **~3297 MiB están libres** al momento de cargar el
modelo — el resto lo consume el propio escritorio de Windows, porque esta GPU también maneja la
pantalla del laptop, no es una tarjeta dedicada solo a cómputo.

Con ese margen real, `gemma3:4b` (Q4_K_M, 4.3B parámetros, contexto 4096) **no entra completo ni
estando solo**: Ollama offloadea capas hasta que caben, quedando en **40% GPU / 60% CPU**.

## Hallazgo 2: mover los embeddings a CPU sí ayuda, pero no resuelve el problema de fondo

`make pin-embeddings-cpu` + `KB_EMBEDDINGS_MODELO=bge-m3-cpu` funciona como se documenta: `bge-m3`
pasa a 100% CPU sin romper la ingesta (la ingesta de `corpus/`/`repos/` es heurística, no pasa por
el LLM — ver `docs/architecture.md` y `package-info.java` del paquete `ingesta`). Libera VRAM, pero
`gemma3:4b` se queda en el mismo 40%/60% incluso sin competir por memoria con los embeddings: el
techo real de este equipo no le alcanza al modelo completo, punto.

**Este cambio (embeddings a CPU) sí quedó aplicado** en `.env`
(`KB_EMBEDDINGS_MODELO=bge-m3-cpu`) — es una mejora neta sin contrapartida de calidad.

## Hallazgo 3: comparación empírica de LLMs alternativos ya descargados

Con `curl` directo a `http://localhost:11434/api/generate` (no `ollama run`, que ensucia la salida
con códigos de control de terminal) y `docker exec kb-ollama ollama ps` después de cada llamada:

| Modelo | Tamaño en disco | Reparto CPU/GPU | Observación |
|---|---|---|---|
| `gemma3:4b` (validado, ver ADR-0008) | 3.3 GB | 60% CPU / 40% GPU | El modelo con el que se calibró todo el pipeline (umbrales de relevancia, prompts) |
| `qwen3:4b` | 2.5 GB | 40% CPU / 60% GPU | Modo "thinking" activado por defecto: **470 s** para responder una palabra. Inviable sin desactivarlo explícitamente (parámetro `think` de Ollama, sin probar) |
| `granite4.1:3b` | 2.1 GB | 19% CPU / 81% GPU | Mejor fit de VRAM de los tres, responde rápido y en español coherente en la etapa de *planificación* — pero el ADR-0008 ya lo había descartado antes por fallar en la etapa de *síntesis* (pega fragmentos crudos con `[n]` sin rellenar, o inventa "correcciones" silenciosas) |

`gemma3:4b-it-qat` (variante con *quantization-aware training* de Google, pensada específicamente
para GPUs de consumo chicas, ~misma calidad que `gemma3:4b` con menor huella) se identificó como el
candidato más prometedor — mismo entrenamiento/calidad, mejor cuantización — pero **la descarga
falló** por mal estado de la red (se quedó en 2%, 79 KB/s, `TLS handshake timeout`). Queda pendiente
reintentarla cuando la conexión mejore.

## Hallazgo 4: benchmarks publicados no predicen el comportamiento real en este pipeline

Vía WebSearch, benchmarks publicados de `gemma3:4b` vs `granite4.1:3b`:

- IFEval (instrucciones): `gemma3:4b` 90.2 vs `granite4.1:3b` 82.1.
- HumanEval (código): `granite4.1:3b` 79.27, notablemente fuerte para su tamaño.

Estos números por sí solos no anticipaban la falla real de síntesis de `granite4.1:3b` documentada
en el ADR-0008 (pegar texto crudo en vez de prosa). Lo mismo aplica al índice más genérico de
[Artificial Analysis](https://artificialanalysis.ai/models/open-source/tiny?openness=openness-vs-intelligence)
("Intelligence Index" agregado de 9 evaluaciones tipo agente/examen): mide capacidad general, no el
comportamiento específico que necesita este pipeline (JSON estructurado, tool-planning, síntesis con
citas en español). Tabla relevada de esa página (modelos ≤4B, ordenados por Intelligence Index):

| Modelo | Organización | Intelligence Index | Parámetros | Licencia conocida |
|---|---|---|---|---|
| G9v3-3B | AI9Stars | 16 | 3B | Desconocida — org poco establecida |
| MiniCPM5-1B | OpenBMB | 12 | 1B | Apache 2.0 |
| Nanbeige4.1-3B | Nanbeige | 11 | 3.9B | Apache 2.0 (típico del lab) |
| NVIDIA Nemotron 3 Nano 4B | NVIDIA | 9 | 4.0B | Licencia NVIDIA (permisiva con condiciones) |
| Qwen3.5 2B (Reasoning) | Alibaba | 8 | 2.3B | Apache 2.0 |
| Ministral 3 3B | Mistral | 7 | 3B | Verificar — Mistral usa licencia no-comercial en algunas variantes chicas |
| Phi-4 Mini Instruct | Microsoft | 6 | 3.8B | MIT |
| Granite 4.1 3B | IBM | 5 | 3B | Apache 2.0 |

`gemma3:4b` y `qwen3:4b` no aparecen en este corte específico de Artificial Analysis (no implica que
sean peores, solo que no están en esta vista). El Openness Index (eje Y del gráfico original) no se
pudo extraer en valores numéricos — el gráfico es un scatter renderizado, no una tabla en el HTML.

Candidatos a investigar cuando la red lo permita, en este orden, **repitiendo la misma metodología
de esta sesión** (verificar disponibilidad en Ollama, medir reparto CPU/GPU real, y sobre todo probar
la síntesis real con `KB_UMBRAL_RELEVANCIA_HABILITADO=false` para no repetir el error de solo mirar
la etapa de planificación):

1. `Nanbeige4.1-3B` — mejor Intelligence Index que Granite, tamaño similar, licencia abierta, sin el
   problema de "thinking" de Qwen3.5.
2. `Phi-4 Mini Instruct` — MIT (la licencia más permisiva de la lista), históricamente fuerte
   siguiendo instrucciones y generando salida estructurada.
3. `MiniCPM5-1B` — si la prioridad es VRAM por encima de todo; a 1B hay más riesgo de que la calidad
   de síntesis se resienta.

## Sesión 2: las pruebas pendientes, ejecutadas

Continuación directa de la sesión anterior: con la red ya sana, se retomaron los tres pendientes
del cierre (`qwen3:4b` con `think`, reintento de `gemma3:4b-it-qat`, y los tres candidatos de la
lista) siguiendo la misma metodología — `curl` directo a `/api/generate`, `ollama ps` para el
reparto real, y ahora además una prueba end-to-end contra `POST /api/ask` con
`KB_UMBRAL_RELEVANCIA_HABILITADO=false` (contenedor `kb-api` temporal vía
`docker compose run -e ...`, sin tocar `.env`) para ver la síntesis real de cada candidato sin que
la puerta de relevancia la tape.

### Hallazgo 5: `qwen3:4b` — el parámetro `think` no apaga el razonamiento, solo cambia dónde aparece

Con `think:false` explícito, el modelo igual generó el razonamiento completo (~130 tokens) pero sin
separarlo en el campo `thinking`: quedó mezclado dentro de `response`, terminando en un `</think>`
literal seguido recién ahí de la respuesta real. Es un bug conocido y ya reportado
([`ollama/ollama#12917`](https://github.com/ollama/ollama/issues/12917), "Can't turn off
thinking"), no un error de uso del parámetro.

Lo que sí cambió respecto a la sesión anterior: la latencia real para una pregunta trivial de una
palabra fue **~18-21 s** (con o sin `think:false`, sin diferencia real), muy lejos de los 470 s
documentados antes. Aun así, sigue siendo caro comparado con `gemma3:4b`: una vez el modelo está
caliente en memoria, `gemma3:4b` responde la misma pregunta trivial en **0.26 s** (3 tokens, sin
razonamiento) contra los ~18 s que `qwen3:4b` paga siempre en tokens de "pensamiento" no
suprimibles. A escala de una consulta real del pipeline (que encadena 2-3 llamadas al LLM:
planificador, verificador de grounding y sintetizador), ese impuesto se paga varias veces por
consulta. Conclusión: **`qwen3:4b` sigue sin ser viable**, no por los 470 s originales (que no se
reprodujeron) sino porque el modo pensamiento no se puede apagar de verdad vía API y su costo por
consulta es estructuralmente mayor al de `gemma3:4b`.

### Hallazgo 6: `gemma3:4b-it-qat` — la descarga funcionó, pero contradijo la hipótesis que motivó probarlo

La descarga esta vez se completó sin problema (la red había mejorado). Pero el resultado invierte
la expectativa de la sesión anterior ("mismo entrenamiento/calidad, mejor cuantización"):

| | `gemma3:4b` (default) | `gemma3:4b-it-qat` |
|---|---|---|
| Cuantización (`ollama show`) | `Q4_K_M` | `Q4_0` |
| Tamaño cargado en memoria (`ollama ps`) | 4.0 GB | 4.6 GB |
| Reparto CPU/GPU | 60% / 40% | 65% / 35% |

El tag oficial `-it-qat` de la librería de Ollama usa `Q4_0` — un esquema de cuantización más
simple y menos eficiente en espacio que el `Q4_K_M` que ya trae el tag `gemma3:4b` por defecto. El
beneficio real de QAT (mismo entrenamiento, cuantización agresiva sin perder calidad) se mide contra
una cuantización naive al mismo ancho de bits, no contra el `Q4_K_M` con el que ya se comparaba acá.
En este pipeline, con esta GPU de 4 GB, **el tag por defecto ya es la opción más liviana** de las
dos — lo contrario de lo que se esperaba al cerrar la sesión anterior.

En la prueba de síntesis real (`KB_UMBRAL_RELEVANCIA_HABILITADO=false`): ante "como se despliega el
servicio" respondió con contenido correcto y relevante, pero **sin los marcadores de cita `[n]`**
esperados (el prompt de citas de `SintetizadorOllama`, afinado para `gemma3:4b`, no se transfiere
igual a esta variante — usó un separador `--.` en su lugar). Ante la pregunta de control fuera de
dominio "explícame cómo usar Java 25", **reprodujo exactamente la misma alucinación** documentada en
el ADR-0008 para el `gemma3:4b` vanilla: pegó las instrucciones de despliegue de este proyecto como
si respondieran la pregunta. Conclusión: **no es el candidato prometedor que se pensaba** — peor
huella de VRAM, mismo defecto de síntesis, y una regresión de formato de citas encima.

### Hallazgo 7: los candidatos con "thinking" nativo (Nanbeige4.1-3B, MiniCPM5-1B) se descontrolan en las etapas de salida estructurada del pipeline

Patrón nuevo, no anticipado por ningún benchmark publicado ni por la prueba aislada contra
`/api/generate`:

- **`tomng/nanbeige4.1:3b-q4_K_M`** (variante correcta para comparar como las demás: la primera
  descarga sin sufijo trajo `Q8_0`, 4.2 GB, y se reemplazó por esta). Contra `/api/generate` solo,
  el reparto CPU/GPU fue **23% / 77%** — el mejor ajuste de VRAM de todos los candidatos probados,
  igual que auguraba el Intelligence Index de la sesión anterior. Pero conectado al pipeline real
  (`POST /api/ask`, umbral desactivado), quedó atascado generando razonamiento sin converger en una
  de las etapas de salida estructurada (`Planificador` o `VerificadorGrounding`, ambas con JSON
  forzado): más de 1800 tokens decodificados sin parar, acercándose al límite de contexto (4096) sin
  producir nunca una respuesta. La consulta se abortó antes de terminar.
- **`openbmb/minicpm5`** (1B, 857 MB cargado, **100% GPU** — el mejor ajuste de VRAM posible).
  Reprodujo el mismo patrón: más de 3600 tokens decodificados sin converger en el pipeline real.
  Además, ya había fallado la prueba de control más trivial posible contra `/api/generate` puro
  ("¿cuál es la capital de Francia?"): su propio razonamiento visible se autocontradijo y concluyó
  **"La capital de Francia no existe."** — un error factual básico, no una cuestión de relevancia ni
  de síntesis.

Ninguno de los dos falló por VRAM (ambos tenían el mejor reparto CPU/GPU del lote) ni por
"pensar mal" el contenido — fallaron porque su modo de razonamiento nativo, al toparse con las
llamadas de salida estructurada que ya usan `Planificador`/`VerificadorGrounding`, no tiene un
mecanismo confiable para converger. `qwen3:4b` (hallazgo 5) comparte la misma causa raíz, solo que
ahí el razonamiento sí termina, apenas caro. Esto no aparece en ningún benchmark publicado ni se
detecta probando el modelo aislado con una sola llamada libre: solo se ve embebido en un pipeline
que fuerza JSON en llamadas intermedias.

### Hallazgo 8: `phi4-mini:3.8b` — sin problema de thinking, pero con el mismo defecto de síntesis que `granite4.1:3b`

Sin capacidad de "thinking" (no aparece en `ollama show`), reparto CPU/GPU **37% / 63%**, respuestas
rápidas y predecibles (124 s la pregunta relevante, 81 s la de control, contra 200-280 s de las
variantes de `gemma3`). Pero la síntesis real reprodujo el defecto que el ADR-0008 ya había
encontrado en `granite4.1:3b`: ante "como se despliega el servicio", **pegó el texto crudo del
fragmento línea por línea** en vez de redactar prosa, con el marcador `[2]` repetido en cada línea
en lugar de una cita bien ubicada — y sin citar el resto de los fragmentos relevantes. Ante la
pregunta de control "explícame cómo usar Java 25", alucinó igual que los demás modelos, pegando las
instrucciones de despliegue como respuesta. Licencia MIT y buen ajuste de VRAM no alcanzan: la falla
de síntesis es la misma que ya descartó a `granite4.1:3b`.

### Conclusión de la sesión 2

Los cinco modelos pendientes (`qwen3:4b`, `gemma3:4b-it-qat`, `tomng/nanbeige4.1:3b-q4_K_M`,
`phi4-mini:3.8b`, `openbmb/minicpm5`) quedaron descartados, cada uno por un motivo distinto:

| Modelo | Motivo de descarte |
|---|---|
| `qwen3:4b` | `think:false` no suprime el razonamiento (bug de Ollama); costo fijo de ~18-21s por llamada |
| `gemma3:4b-it-qat` | Peor huella de VRAM que el `gemma3:4b` default (Q4_0 vs Q4_K_M) + misma alucinación del ADR-0008 + sin citas `[n]` |
| `tomng/nanbeige4.1:3b-q4_K_M` | Mejor ajuste de VRAM del lote, pero razonamiento sin converger (>1800 tokens) en las llamadas de salida estructurada |
| `openbmb/minicpm5` | Mejor ajuste de VRAM posible (100% GPU), pero falla hasta una pregunta factual trivial y también se descontrola (>3600 tokens) |
| `phi4-mini:3.8b` | Sin problema de latencia ni de thinking, pero mismo defecto de "pegar texto crudo" que ya descartó a `granite4.1:3b` |

Ningún candidato probado en las dos sesiones supera la barra de calidad de síntesis que
`gemma3:4b` ya tiene validada (tabla final del ADR-0008). El patrón más valioso de esta sesión es
transversal, no de un modelo puntual: **la capacidad nativa de "thinking" es un riesgo estructural
para este pipeline**, porque las etapas de salida forzada (JSON del planificador, clasificación
binaria del verificador de grounding) no garantizan que ese razonamiento converja — y ni el
Intelligence Index ni el ajuste de VRAM lo predicen. Los modelos sin "thinking" (`gemma3*`,
`phi4-mini`, `granite4.1:3b`) al menos fallan de forma predecible (alucinación o pegado de texto
crudo), lo cual es más fácil de diagnosticar y eventualmente mitigar con prompt tuning que un
timeout impredecible.

## Sesión 3: revisando el hallazgo 6 — la sobre-citación no es de `-it-qat`, es de `gemma3:4b` también

El hallazgo 6 (sesión 2) achacaba la falta de citas `[n]` bien ubicadas a la variante
`gemma3:4b-it-qat`. Antes de invertir en prompt tuning, se corrió la misma pregunta ("como se
despliega el servicio", umbral desactivado) contra el **`gemma3:4b` default** — el modelo
recomendado y validado — para tener una línea base limpia. Resultado: **el defecto también está en
`gemma3:4b`**, solo que de otra forma. En vez de omitir las citas (lo que hacía `-it-qat`),
`gemma3:4b` sí las pone, pero **sobre-cita**: pegó el mismo cluster de tres marcadores
`[1], [2], [4]` al final de casi cada oración de una respuesta de varios pasos, en vez de atribuir
a cada paso la única fuente puntual que lo respalda. El hallazgo 6 describía un síntoma de
`-it-qat`; la causa (el pipeline arma el contexto con varios fragmentos consecutivos del mismo
documento, y el modelo no distingue cuál paso viene de cuál fragmento) es del modelo base.

### Dos intentos de prompt tuning sobre `SintetizadorOllama`, ninguno funcionó

**Intento 1 — regla explícita.** Se agregó al prompt de sistema una instrucción puntual: "un solo
marcador `[n]` por afirmación, el de la única fuente que la respalda, nunca `[1], [2], [4]` pegados
en cada oración", con el patrón exacto observado como "error visto en producción". Resultado:
**sin cambio medible** — la respuesta fue prácticamente idéntica a la línea base, mismo cluster de
tres marcadores repetido en cada paso.

**Intento 2 — ejemplo few-shot concreto.** Se reemplazó la regla abstracta por un ejemplo completo:
un contexto de tres fragmentos ficticios y la transformación correcta paso-a-paso (una cita por
paso) contrastada explícitamente con la incorrecta (todas las fuentes citadas en cada paso, marcada
como "el error más común, evitalo"). Resultado: **peor que la línea base, no igual**. La respuesta
quedó truncada a 2 de los 5 pasos reales, volvió al estilo de pegar texto casi textual del fragmento
("1. Copia `.env.example` a `.env`.") en vez de prosa propia, y **igual siguió citando
`[1], [2], [4]` en cada paso** — el few-shot no transfirió el patrón de atribución que mostraba, y
además indujo un estilo de respuesta más pobre y corto. Ningún error ni advertencia en la respuesta:
el modelo simplemente decidió parar antes y en un registro distinto.

Ambos cambios se revirtieron por completo (`SintetizadorOllama.java` quedó igual a como estaba al
empezar esta sesión) después de reconstruir la imagen y verificar que el archivo coincide con el
original. Ninguno de los dos experimentos quedó aplicado.

**Conclusión:** el defecto de sobre-citación (hallazgo 6) no se resuelve con ajustes menores al
prompt de `SintetizadorOllama`, ni con una regla abstracta ni con un ejemplo concreto — al menos no
con dos intentos de esta forma. Es consistente con la lección ya anotada en el ADR-0008 sobre
`VerificadorGrounding`: parchar el prompt caso por caso tiene rendimientos decrecientes a esta
escala de modelo. Vías no probadas todavía que podrían tener más chance: (a) forzar que la síntesis
misma sea salida estructurada (un array de `{afirmacion, fuente}` en vez de prosa libre con
marcadores incrustados, mismo patrón que ya usa `PlanificadorOllama`/`VerificadorGrounding`), lo que
elimina el problema de raíz en vez de pedirle al modelo que "se porte bien" en texto libre; o (b)
numerar los fragmentos de forma menos contigua/menos asociable a un único documento cuando vienen
del mismo archivo, para que el modelo no los trate como un bloque intercambiable.

## Estado en que quedó el entorno al cierre de esta sesión

- `.env`: sin cambios respecto a la sesión anterior — `KB_EMBEDDINGS_MODELO=bge-m3-cpu`
  (permanente) y `KB_LLM_MODELO=granite4.1:3b` (**temporal**, igual que antes).
- `SintetizadorOllama.java`: sin cambios netos — los dos experimentos de prompt tuning de esta
  sesión se revirtieron por completo; el archivo quedó idéntico al que había al empezar. La imagen
  `base-conocimiento-api` se reconstruyó dos veces durante las pruebas y una vez más al revertir,
  y el `kb-api` real quedó corriendo la versión revertida (sin cambio de comportamiento respecto a
  antes de esta sesión).
- Todas las pruebas de las sesiones 2 y 3 se hicieron contra un contenedor `kb-api-test` temporal
  (`docker compose run -e KB_LLM_MODELO=... -e KB_UMBRAL_RELEVANCIA_HABILITADO=false`), nunca
  contra el `.env` real.
- Ollama (`kb-ollama`) quedó con 5 modelos de la sesión 2 descargados y sin borrar, por si sirven
  para retomar esto más adelante (850+ GB libres, sin urgencia de limpiarlos): `qwen3:4b`,
  `gemma3:4b-it-qat`, `tomng/nanbeige4.1:3b-q4_K_M`, `phi4-mini:3.8b`, `openbmb/minicpm5`.
- **Nota operativa nueva de esta sesión**: una generación que se descontrola (hallazgo 7, modelos
  con "thinking") no muere sola aunque el cliente HTTP se rinda — sigue consumiendo el slot de
  Ollama en el servidor hasta que alguien la corta. Si una prueba contra un modelo con "thinking"
  se cuelga, `docker restart kb-ollama` es la forma rápida de liberar el slot antes de seguir
  probando otro modelo.
- Recomendación por defecto para producción/demo, sin cambios: volver a `KB_LLM_MODELO=gemma3:4b`.
  Sigue siendo el modelo con mejor comportamiento medido de los ocho probados en tres sesiones,
  pero **su defecto de sobre-citación (hallazgo 6) queda sin resolver** — dos intentos de prompt
  tuning no lo arreglaron. La vía más prometedora para seguir no es un tercer ajuste de prompt en
  texto libre, sino explorar salida estructurada para la síntesis (ver arriba), o aceptar el
  defecto de citas como limitación conocida si el contenido en sí sigue siendo correcto.

## Sesión 4: buscando un modelo que entre 100% en la VRAM de la T600 — ninguno pasa la barra de calidad

Pregunta puntual de esta sesión: de los ocho modelos probados hasta ahora, ninguno entra 100% en
GPU salvo `openbmb/minicpm5` (que falla hasta una pregunta factual trivial). ¿Hay algún modelo de
~2-3B, sin "thinking" nativo, que sí entre completo y mantenga una calidad de síntesis aceptable?
Misma metodología: `ollama pull`, `ollama ps` para el reparto real, y prueba end-to-end contra
`POST /api/ask` con `KB_UMBRAL_RELEVANCIA_HABILITADO=false` en un `kb-api-test` temporal.

### Hallazgo 9: tres candidatos nuevos, uno solo entra 100% en GPU — y ese falla la síntesis igual que `granite4.1:3b`

| Modelo | Tamaño cargado (`ollama ps`) | Reparto CPU/GPU | Licencia |
|---|---|---|---|
| `qwen2.5:3b` | 2.3 GB | **100% GPU** | Qwen RESEARCH LICENSE (no Apache 2.0, a diferencia de otros tamaños de Qwen2.5) |
| `gemma2:2b` | 2.2 GB | 90% GPU / 10% CPU | Gemma Terms of Use |
| `llama3.2:3b` | 3.1 GB | 75% GPU / 25% CPU | Llama 3.2 Community License |

Reproducible tras reiniciar `kb-ollama` en frío para cada uno (sin contaminación entre pruebas).
Nota aparte: `gemma2:2b` pesa menos en disco que `qwen2.5:3b` pero termina con peor reparto —
confirma lo que ya advertía el hallazgo 4: el tamaño en disco no predice el ajuste de VRAM, cada
arquitectura reparte la cache KV distinto.

`qwen2.5:3b` fue el único candidato de esta sesión que entra 100% en GPU. En la prueba de
síntesis real (`POST /api/ask`, umbral desactivado):

- **Pregunta relevante** ("como se despliega el servicio"): el plan JSON del `Planificador` salió
  bien formado (sin el problema de convergencia del hallazgo 7). Pero la síntesis **pegó los
  fragmentos casi textuales, uno detrás de otro, con una sola cita `[1]` al principio** para un
  párrafo que en realidad mezcla contenido de tres fragmentos distintos — el mismo defecto de
  "pegado de texto crudo" que ya había descartado a `granite4.1:3b` y `phi4-mini:3.8b` (hallazgos 3
  y 8), aquí incluso peor en cobertura de citas.
- **Pregunta de control fuera de dominio** ("explícame cómo usar Java 25"): reprodujo **exactamente
  la misma alucinación del ADR-0008** — pegó las instrucciones de despliegue de este proyecto como
  si respondieran la pregunta.

`llama3.2:3b` (75% GPU) y `gemma2:2b` (90% GPU) no se llevaron a la prueba de síntesis real: ya
incumplen el criterio de esta sesión (100% VRAM), así que no tenía sentido gastar el tiempo de
carga en frío (~5 min cada uno) solo para confirmar un segundo motivo de descarte.

### Hallazgo 10: reducir `num_ctx` no mueve el reparto de `gemma3:4b`

Antes de descartar la vía de "achicar el modelo ganador en vez de cambiar de modelo", se probó
`gemma3:4b` con `options.num_ctx=2048` (la mitad del default) contra `/api/generate` puro, en
frío, dos veces (una con contexto default como línea base de esta sesión, otra con 2048):

| Configuración | Tamaño cargado | Reparto CPU/GPU |
|---|---|---|
| `gemma3:4b`, contexto 4096 (default) | 4.0 GB | 60% / 40% |
| `gemma3:4b`, contexto 2048 | 4.0 GB | 60% / 40% — sin cambio |

Confirma que el cuello de botella no es la cache KV (que sí depende del contexto) sino el peso del
modelo en sí (~3.3 GB en disco): a esta escala de contexto, la cache KV es una fracción tan chica
del total que ni duplicarla ni reducirla a la mitad mueve la aguja. Reducir el contexto no es un
camino viable para meter `gemma3:4b` completo en esta GPU.

### Conclusión de la sesión 4

**No hay alternativa conocida, de las 11 probadas en cuatro sesiones, que entre 100% en la VRAM de
la T600 y a la vez pase la barra de calidad de síntesis de `gemma3:4b`.** El único modelo que
cumple el criterio de VRAM (`qwen2.5:3b`) falla la síntesis de la misma forma que ya había
descartado a otros dos candidatos, y además reproduce la alucinación de control del ADR-0008.
El único modelo que entra 100% en GPU y no fue descartado por una falla básica (`openbmb/minicpm5`,
sesión 2) falla por otro motivo: se descontrola en las llamadas de salida estructurada del
pipeline.

Esto deja el mapa de esta GPU en un trade-off explícito, no en un empate a resolver con más
pruebas de modelos nuevos:

- **Mejor calidad, no entra 100% en VRAM**: `gemma3:4b` (60%/40%, defecto de sobre-citación
  conocido y sin resolver).
- **Entra 100% en VRAM, peor calidad**: `qwen2.5:3b` (pegado de texto crudo + alucinación de
  control), o `openbmb/minicpm5` (falla hasta preguntas factuales triviales).

No quedan vías baratas sin probar para cerrar esta brecha con lo que ya se investigó: reducir
contexto no ayuda (hallazgo 10), y no existe en la librería de Ollama una variante de `gemma3:4b`
con mejor cuantización que el `Q4_K_M` por defecto (el hallazgo 6 ya descartó `-it-qat`, que es
peor). Vías no probadas, fuera del alcance de esta sesión: cuantizar `gemma3:4b` manualmente a un
esquema más agresivo (`Q3_K_M` o `IQ4_XS`) vía `llama.cpp`/`Modelfile` propio en vez de un tag
oficial de Ollama, aceptando el riesgo de degradar la calidad que sí funciona hoy; o mover la
síntesis a un servicio externo (fuera del objetivo de "costo cero" de este proyecto, ver README).

Estado del entorno al cierre: `.env` sin cambios (`KB_LLM_MODELO=granite4.1:3b`, sigue temporal).
Los tres modelos de esta sesión (`qwen2.5:3b`, `llama3.2:3b`, `gemma2:2b`) quedaron descargados en
`kb-ollama` junto a los cinco de la sesión 2, sin borrar. El contenedor `kb-api-test` usado para
las pruebas de síntesis se eliminó al cerrar. Nota operativa: en un punto de esta sesión se
recreó `kb-ollama` sin el override de GPU (`docker compose run` sin `-f compose.gpu.yml` toma la
definición base de `compose.yml`, que no reserva el dispositivo NVIDIA) — quedó corriendo unos
minutos en CPU pura antes de detectarse y corregirse con
`docker compose -f compose.yml -f compose.gpu.yml up -d`. Cualquier `docker compose run`/`up`
contra el servicio `api` en esta máquina necesita los dos archivos compose explícitos, o el
`ollama` real pierde la GPU.

## Sesión 5: `Bonsai 8B` (PrismML) — entra sobrado en VRAM, la mejor citación medida, pero comparte la alucinación universal de control

Encontrado fuera de esta serie de sesiones (anuncio de PrismML, marzo 2026). A diferencia de las
sesiones 1-4, todos los modelos previos eran cuantización post-entrenamiento de un modelo en punto
flotante; `Bonsai 8B` se entrenó **nativamente en 1-bit** desde cero — cada peso es −scale/+scale,
con una escala FP16 compartida cada 128 pesos (1.125 bits efectivos por peso). Resultado: 8.2 mil
millones de parámetros en 1.15 GB de disco. Apache 2.0, contexto de 65,536 tokens, pesos en
[Hugging Face](https://huggingface.co/prism-ml/Bonsai-8B-gguf).

### Hallazgo 11: no corre en Ollama — hubo que compilar el fork y probarlo fuera del pipeline

El GGUF usa un tipo de cuantización propio (`Q1_0 (g128)`) con kernels que solo existen en un fork
propio de llama.cpp ([`PrismML-Eng/llama.cpp`](https://github.com/PrismML-Eng/llama.cpp)) — el
Ollama estándar no lo soporta. Se compiló el fork con `GGML_CUDA=ON` y
`CMAKE_CUDA_ARCHITECTURES=75` (Turing, la generación de la T600) dentro de un contenedor
`nvidia/cuda:12.6.0-devel-ubuntu22.04`, sin tocar `compose.yml` ni el cliente de Spring AI: la
prueba fue con `llama-cli` suelto, pegando a mano el mismo prompt de sistema de
`SintetizadorOllama.java` y el mismo contexto (fragmentos numerados `[n]`) que arma
`Orquestador.construirContexto()`, para que la comparación con las sesiones anteriores fuera
directa.

**VRAM real medida con `nvidia-smi` durante la generación**: **1759 MiB, 100% de utilización de
GPU** — menos de la mitad de los ~3.9 GB libres de esta máquina, con margen de sobra incluso frente
a `qwen2.5:3b` (2.3 GB). Confirma la promesa de VRAM: es, por lejos, el modelo que más entra
sobrado de los doce probados en esta investigación.

**Velocidad**: 5.8 tokens/segundo de generación, más lento de lo que sugieren los benchmarks de
PrismML (368 tok/s en RTX 4090). Explicación más probable: esos números se midieron en Ada Lovelace
(RTX 4090), con soporte de tensor cores para operaciones de bits bajos que Turing (T600, 2018) no
tiene — el fork corre igual en esta GPU, pero sin el camino de kernel optimizado que sí existe en
hardware más nuevo. No se investigó a fondo (sesión de smoke test, no de perfilado).

### Hallazgo 12: en la pregunta relevante, la mejor citación de toda la investigación

Pregunta "como se despliega el servicio", mismo contexto de 4 fragmentos que usaron `gemma3:4b` y
`qwen2.5:3b` en las sesiones anteriores. Respuesta de Bonsai:

> Para despliegar el servicio, se necesita Docker Desktop con el motor iniciado [1]. Además, en
> Windows, se debe copiar `wslconfig.example`... WSL2 [1]. 1. Copia `.env.example` a `.env` [2].
> 2. Corre `docker compose up -d`... [2]. (...) Si la máquina tiene una GPU NVIDIA... [4].

A diferencia de `gemma3:4b` (sobre-citación: agrupa `[1], [2], [4]` al final de cada oración,
hallazgo 6) y de `qwen2.5:3b`/`granite4.1:3b`/`phi4-mini:3.8b` (pegan texto crudo con una sola cita
suelta al principio, hallazgos 3, 8 y 9): Bonsai puso **una sola cita correcta al final de cada
afirmación puntual**, exactamente como pide el prompt de sistema, e **ignoró el fragmento `[3]`
irrelevante** (el README sobre la carpeta `corpus/`) en vez de citarlo por citar. Es el único
candidato de los doce que respetó el formato de citación al pie de la letra.

### Hallazgo 13: en la pregunta de control, reprodujo la alucinación del ADR-0008 — y con peor formato de cita

Pregunta de control "explícame cómo usar Java 25", mismo contexto (los mismos 4 fragmentos
reordenados). Respuesta:

> Para usar Java 25, necesitas asegurarte de que tu entorno esté configurado correctamente. [1] Se
> requiere Docker Desktop con el motor iniciado. [2] En Windows, además hay que copiar
> `wslconfig.example`... [3] Si la máquina tiene una GPU NVIDIA... [4] Deja aquí tus documentos...

Dos problemas, no uno: **(a)** reprodujo exactamente la misma alucinación de control documentada en
el ADR-0008 y en los hallazgos 6 y 9 — en vez de decir "el contexto no alcanza para responder esto"
(la instrucción explícita del prompt de sistema), pegó las instrucciones de despliegue como si
respondieran la pregunta; **(b)** el marcador `[n]` quedó **antes** de cada afirmación en vez de
pegado al final — justo el "ejemplo incorrecto" que el prompt de sistema cita textualmente para
prohibirlo — y con al menos un desajuste de atribución (`[1]` antecede el contenido de Docker
Desktop, que en este contexto es el fragmento `[2]`).

Nota de alcance: esta prueba corrió con `KB_UMBRAL_RELEVANCIA_HABILITADO=false`, igual que todas
las de las sesiones 2-4 — es decir, con la puerta de relevancia y el `VerificadorGrounding` del
ADR-0008 apagados a propósito, precisamente el mecanismo que en producción (`habilitado: true` por
defecto) existe para atajar este escenario antes de llegar a síntesis. Todos los candidatos
probados hasta ahora fallan esta pregunta de control de la misma forma con la puerta apagada; el
hallazgo 14 (abajo) prueba si esa puerta, con Bonsai en el rol de `VerificadorGrounding`, sí la
atajaría antes de llegar a síntesis.

### Hallazgo 14: como `VerificadorGrounding` con salida JSON forzada, acertó los dos veredictos del ADR-0008

Se probó el segundo rol del pipeline que exige salida estructurada — el que el hallazgo 7 (sesión 2)
ya había señalado como el punto ciego real de cualquier candidato (ni el Intelligence Index ni el
ajuste de VRAM lo predicen; hay que probarlo). Mismo prompt de sistema y mismo formato de mensaje
(`PREGUNTA: ...\n\nCONTEXTO:\n...`) que `VerificadorGroundingOllama.java`, `--temp 0` igual que la
implementación real, y el mismo par de casos que el ADR-0008 usa como ejemplo canónico de la zona
ambigua: "explícame cómo usar Java 25" (esperado `false`) y "como se despliega el servicio"
(esperado `true`), ambos contra el mismo contexto de 4 fragmentos de las pruebas anteriores.

**Primer intento, con `-j`/`--json-schema` (el mecanismo más parecido a `useProviderStructuredOutput()`
de Spring AI/Ollama): falló.** El fork aborta con `Failed to initialize samplers: std::exception` al
convertir el JSON Schema a gramática interna — un bug de esta build del fork de PrismML, no del
modelo. **Workaround: pasar la gramática GBNF a mano** (`--grammar-file`) en vez de dejar que
`-j` la genere — con eso el sampler de gramática funciona sin problema.

Con el workaround, los dos veredictos salieron **correctos**:

| Caso | Esperado (ADR-0008) | Veredicto de Bonsai |
|---|---|---|
| "explícame cómo usar Java 25" | `false` | `{"respondeLaPregunta": false}` ✅ |
| "como se despliega el servicio" | `true` | `{"respondeLaPregunta": true}` ✅ |

Esto es lo más importante que arrojó esta sesión: **el defecto de síntesis del hallazgo 13
(alucinar la respuesta de Java 25) no llegaría a manifestarse en producción si Bonsai también hiciera
de `VerificadorGrounding`**, porque la puerta que existe justamente para atajar ese caso sí lo
detecta correctamente con este modelo. La alucinación de la sesión 5 solo aparece cuando se prueba
la síntesis aislada con la puerta apagada — que es la metodología de esta y las sesiones 2-4, elegida
a propósito para comparar candidatos en igualdad de condiciones, no un reflejo de cómo se comportaría
el sistema completo.

### Hallazgo 15: como `Planificador`, elige bien pero es menos preciso que `gemma3:4b`/`qwen2.5:3b`

Mismo catálogo de seis herramientas y mismo prompt de sistema que `PlanificadorOllama.java`
(incluida la advertencia explícita sobre la confusión "requisitos de hardware/despliegue se
responden con documentación, no con código"), gramática GBNF a mano para `PlanDeHerramientas`
(`herramientas: string[]`, `razon: string`) por el mismo bug del hallazgo 14. Dos casos:

| Pregunta | Esperado | Plan de Bonsai |
|---|---|---|
| "como se despliega el servicio" | sin `search_code`; `search_docs`/`search_unified` | `["search_docs", "search_unified", "who_knows", "subsystem_index"]` |
| "como esta implementada la fusion RRF en el codigo" | con `search_code` | `["search_code"]` |

Acertó lo esencial en los dos casos: nunca cayó en la confusión puntual que el prompt advierte
(no eligió `search_code` para la pregunta de despliegue), y para la pregunta de código eligió
exactamente `search_code`, solo, con la razón correcta. Pero en el primer caso fue menos preciso que
`gemma3:4b`/`qwen2.5:3b` en las sesiones anteriores (que eligieron limpio `["search_docs",
"search_unified"]`, ver la respuesta real de la sesión 4): agregó `who_knows` y `subsystem_index`,
dos herramientas que no encajan con una pregunta puntual de "cómo se hace X" (son para "¿dónde está
documentado?" y para panorama general, respectivamente, según sus propias descripciones en el
catálogo). No es una falla de convergencia como la del hallazgo 7 (`nanbeige4.1:3b`/`minicpm5`
nunca llegaban a una respuesta) ni una elección errónea que rompa el pipeline (herramientas de más
solo cuestan latencia extra en el fan-out, no producen una respuesta peor) — es una imprecisión
menor, no un descarte.

### Conclusión de la sesión 5

**Bonsai 8B es, de los doce candidatos probados en esta investigación, el que mejor combina los
tres roles del pipeline con salida forzada o citación: VRAM, citación en síntesis, y las dos salidas
JSON estructuradas (`VerificadorGrounding` y `Planificador`).** Resumen:

- **VRAM**: 1.76 GB reales medidos con `nvidia-smi`, 100% GPU — menos de la mitad del presupuesto de
  la T600, el mejor ajuste de los doce candidatos.
- **Síntesis en la pregunta relevante**: la mejor citación medida en toda la investigación (hallazgo
  12) — mejor que `gemma3:4b`.
- **`VerificadorGrounding`**: acertó los dos veredictos canónicos del ADR-0008 con salida JSON
  forzada (hallazgo 14) — el modo de falla que descartó a `nanbeige4.1:3b` y `minicpm5` (sesión 2)
  simplemente no apareció acá.
- **`Planificador`**: eligió bien en los dos casos probados, sin caer en la confusión
  código-vs-documentación que el prompt advierte explícitamente, aunque con menos precisión que
  `gemma3:4b`/`qwen2.5:3b` en el caso simple (hallazgo 15).
- **Síntesis en la pregunta de control, con la puerta apagada**: alucina igual que los otros once
  candidatos (hallazgo 13) — pero el hallazgo 14 indica que, con la puerta encendida como corre en
  producción, ese caso puntual no llegaría a síntesis.

Con los tres roles del pipeline ya probados de forma aislada (síntesis, verificador, planificador),
**no queda ningún modo de falla estructural pendiente de los que esta investigación identificó como
puntos ciegos de un benchmark genérico** (hallazgo 7: convergencia en salida forzada; hallazgo 4:
comportamiento real vs. benchmark publicado). Falta, antes de una recomendación real:

1. **Decidir la integración de arquitectura**: sigue sin correr en Ollama. Para usarlo en el
   pipeline real hay que compilar el fork con `GGML_CUDA=ON` en la imagen de `ollama` (o agregar un
   cuarto servicio `llama-server` en `compose.yml`) y cambiar el cliente de Spring AI de
   `OllamaChatModel` a `OpenAiChatModel` apuntando a esa API compatible con OpenAI — trabajo de
   arquitectura, no una variable de entorno. Además, si se integra, hay que generar la gramática GBNF
   a mano para cada tipo de salida estructurada (`Veredicto`, `PlanDeHerramientas`), dado el bug
   de `-j`/`--json-schema` del hallazgo 14.

### Hallazgo 16: el mecanismo de la puerta de relevancia sí funciona end-to-end en producción — validado con `gemma3:4b`, no con Bonsai

Pendiente cerrado con una aclaración de alcance importante: **no se pudo correr esta prueba con
Bonsai** en el rol real, porque Bonsai no está integrado al pipeline (ver el punto 1 de arriba, sin
resolver todavía) — no hay forma de que `kb-api` le hable sin antes hacer el trabajo de
arquitectura. Lo que sí se hizo fue correr el pipeline real completo
(`kb-api` + `kb-ollama`, un `kb-api-test` temporal) con **la puerta de relevancia en su valor por
defecto de producción** (`KB_UMBRAL_RELEVANCIA_HABILITADO=true`, sin overridear como en las
sesiones 2-5) y `gemma3:4b` — el único modelo con evidencia suficiente para confiar en su
`VerificadorGrounding` dentro de los que sí corren en Ollama hoy — contra los mismos dos casos.

| Pregunta | Con la puerta encendida |
|---|---|
| "explícame cómo usar Java 25" (control) | **Interceptada**: `"No encontré información suficientemente relevante en la base de conocimiento para responder esto..."`, `citas: []` — el mensaje `MENSAJE_SIN_INFORMACION`, no la alucinación del hallazgo 13. |
| "como se despliega el servicio" (relevante) | Respuesta normal, con citas — la puerta no genera un rechazo falso sobre un caso legítimo. |

Esto confirma que el mecanismo del ADR-0008 (puerta numérica + `VerificadorGrounding`) cumple su
función en el sistema real tal como está hoy desplegado. Combinado con el hallazgo 14 (el veredicto
aislado de Bonsai para este mismo caso de control ya dio `false`, el valor correcto), da bastante
confianza en que Bonsai como `VerificadorGrounding` produciría el mismo resultado protegido si
llegara a estar integrado — pero es una inferencia por composición de dos pruebas separadas, no una
prueba directa de "Bonsai corriendo los tres roles en el pipeline real", que sigue bloqueada por el
pendiente de arquitectura.

Nota aparte, sin relación con Bonsai: la latencia de ambas llamadas fue alta (398 s y 546 s) porque
corrieron dos preguntas en paralelo contra el mismo `gemma3:4b` (60%/40% CPU/GPU, ver hallazgo 1) —
no es una medición limpia de latencia individual, solo confirma que las respuestas fueron correctas.

Estado del entorno al cierre: el fork se compiló una vez y se probó en un contenedor `bonsai-build`
efímero (`nvidia/cuda:12.6.0-devel-ubuntu22.04`, GPU passthrough vía `docker run --gpus all`),
recreado tres veces a lo largo de la sesión reutilizando el mismo build ya compilado en el volumen
del scratchpad, y eliminado al terminar cada vez. El GGUF (`Bonsai-8B-Q1_0.gguf`, 1.16 GB) y los
archivos de prompt de esta sesión (incluidas las gramáticas `veredicto.gbnf` y `plan.gbnf`) quedaron
en el scratchpad de la sesión, fuera del repo. La prueba del hallazgo 16 usó además un `kb-api-test`
temporal (imagen `base-conocimiento-api` ya construida, sin cambios) contra el `kb-ollama` real con
`gemma3:4b`, eliminado al cerrar — en ningún momento de esta sesión se tocó `compose.yml`,
`Dockerfile` ni `.env` del proyecto real.

**Decisión formal sobre el punto 1 (integración de arquitectura)**: pospuesta, con la evaluación de
costo/riesgo completa en [ADR-0009](adrs/0009-bonsai-8b-integracion-pospuesta.md) — el fork de un
solo proveedor recién salido de stealth, el aumento de complejidad de build que va en contra de la
simplicidad que este proyecto declara como parte de su propuesta de valor, y que la ganancia de
velocidad que promete PrismML no se sostuvo en esta GPU (Turing) son las razones concretas. El ADR
deja condiciones puntuales para reabrir la decisión más adelante.

## Sesión 6: prototipo real de `llama-server` en Docker, y primera prueba con Spring AI

Disparada por la pregunta "¿qué haría falta para que esto funcione con docker-compose?" sobre el
punto 1 de la lista de arquitectura del ADR-0009. A diferencia de la sesión 5 (compilación manual,
`llama-cli` suelto, pegando prompts a mano), acá se construyó el contenedor real y se probó contra
la API HTTP que usaría Spring AI — en una rama aislada (`worktree-experimento+bonsai-llama-server`),
sin tocar `main`, `compose.yml` ni el cliente de Spring AI real. Los tres archivos nuevos
(`Dockerfile.bonsai`, `compose.bonsai.yml`, `entrypoint-bonsai.sh`) quedaron comiteados solo en esa
rama.

### Hallazgo 17: dos bugs de build nuevos, no vistos en la sesión 5 porque ahí no se armó una imagen Docker

La sesión 5 compiló el fork a mano dentro de un contenedor efímero ya con la GPU montada
(`docker run --gpus all`). Un `docker build` normal no tiene la GPU disponible en tiempo de
construcción, y eso saco a la luz dos problemas que la compilación manual no atraviesa:

- **Link final roto**: `nvidia/cuda:12.6.0-devel-ubuntu22.04` no trae el driver CUDA real
  (`libcuda.so`, las funciones `cuMem*` de VMM) porque el build no tiene GPU — ese driver llega
  recién en runtime vía `nvidia-container-toolkit`. Sin él, el link de `llama-server` fallaba con
  `undefined reference to cuMemCreate` y similares. Arreglo: `-DGGML_CUDA_NO_VMM=ON` al configurar
  cmake, que evita depender de esa vía de asignación de memoria. No afecta que el modelo entre o no
  en VRAM — Bonsai sigue midiendo ~1.75 GB (ver hallazgo 18), sobra margen en la T600.
- **`libgomp1` faltante en runtime**: la imagen `nvidia/cuda:12.6.0-runtime-ubuntu22.04` no trae el
  runtime de OpenMP que usan las operaciones CPU de `ggml`. Sin el paquete, `llama-server` fallaba
  al arrancar con `error while loading shared libraries: libgomp.so.1`.

Ninguno de los dos es un problema del modelo ni de la cuantización — son gaps de la imagen base
`nvidia/cuda` sin GPU en build-time, y quedan resueltos en `Dockerfile.bonsai`.

### Hallazgo 18: VRAM y velocidad reales del contenedor, consistentes con la sesión 5

Con el contenedor levantado (`docker compose -f compose.yml -f compose.gpu.yml -f compose.bonsai.yml
up -d llama-server`) y `nvidia-smi` corriendo en paralelo:

| Métrica | Sesión 5 (`llama-cli` suelto) | Sesión 6 (contenedor real) |
|---|---|---|
| VRAM | 1759 MiB | **1753 MiB** |
| Velocidad de generación | 5.8-6.1 tok/s | **6.9 tok/s** |

Confirma que empaquetar el fork en Docker no cambia el comportamiento medido en la sesión 5 — el
contenedor no le agrega overhead relevante ni a VRAM ni a velocidad.

### Hallazgo 19: el bug de `-j`/`--json-schema` del hallazgo 14 no se reproduce en la API runtime de `llama-server`

Este es el hallazgo más importante de la sesión, porque revierte parcialmente el punto 4 de la lista
de arquitectura del ADR-0009. El hallazgo 14 (sesión 5) documentó que el flag `-j`/`--json-schema`
de `llama-cli`/`llama-server` fallaba con `Failed to initialize samplers: std::exception`, y que por
eso hacía falta escribir gramáticas GBNF a mano.

Probado ahora contra `POST /v1/chat/completions` con `response_format: {"type": "json_schema",
"json_schema": {..., "strict": true}}` — el mecanismo exacto que usa
`ChatClient.entity(..., spec -> spec.useProviderStructuredOutput())` de Spring AI contra un
proveedor OpenAI — **no crasheó**, y acertó los dos veredictos canónicos del ADR-0008:

| Caso | Esperado | Veredicto (API runtime) |
|---|---|---|
| "explícame cómo usar Java 25" | `false` | `{"respondeLaPregunta": false}` ✅ |
| "como se despliega el servicio" | `true` | `{"respondeLaPregunta": true}` ✅ |

Con el catálogo y el prompt de sistema reales de `PlanificadorOllama.java` (no una versión
resumida), el plan de herramientas salió más preciso que en la sesión 5 (que había usado una
gramática GBNF escrita a mano contra `llama-cli`):

| Pregunta | Sesión 5 (GBNF a mano, `llama-cli`) | Sesión 6 (`response_format` json_schema, API runtime) |
|---|---|---|
| "como se despliega el servicio" | `["search_docs", "search_unified", "who_knows", "subsystem_index"]` (de más) | `["search_docs", "search_unified"]` — limpio, igual que `gemma3:4b`/`qwen2.5:3b` en la sesión 4 |
| "como esta implementada la fusion RRF en el codigo" | `["search_code"]` | `["search_code"]` — igual |

Conclusión: el bug del hallazgo 14 parece ser específico de como `llama-cli`/el flag CLI convierte el
JSON Schema a gramática, no de la ruta que toma `llama-server` para `response_format` en una request
HTTP normal. Esto sugiere que **el punto 4 de la lista de arquitectura del ADR-0009 (gramáticas GBNF
escritas a mano) probablemente no hace falta** si la integración se hace vía Spring AI/API HTTP en
vez de vía CLI — pero es una inferencia de una sola sesión de pruebas, no una garantía; conviene
re-confirmar si se retoma la integración de verdad.

### Hallazgo 20: gap de sampling nuevo — repetición completa de la respuesta sin `repeat_penalty`, y una regresión de citación al corregirlo

Al probar la síntesis en streaming (`stream: true`, el mecanismo que usa
`Sintetizador.sintetizar()` vía `ChatClient...stream().content()`) con el prompt de sistema real de
`SintetizadorOllama.java` y los mismos 4 fragmentos que la sesión 5, con `temperature: 0` y sin
ningún otro parámetro de sampling:

**La respuesta completa salió duplicada literalmente dos veces**, un modo de falla que ninguna
sesión anterior había visto (ni la 1-4 con los otros modelos, ni la 5 con `llama-cli`). Causa
probable: `llama-cli` aplica un `repeat_penalty` por defecto que la API HTTP de `llama-server` no
aplica sola si no se pide explícito.

Agregando `repeat_penalty: 1.1` al request, la duplicación desapareció, pero aparecieron dos
problemas de calidad que la sesión 5 no había medido en esta combinación exacta:

- Citó el fragmento `[3]` (la carpeta `corpus/`, irrelevante para "como se despliega el servicio")
  — el hallazgo 12 de la sesión 5 había medido justamente que Bonsai lo ignoraba correctamente.
- Puso una cita antes de la afirmación que respalda (`"Para configurar Docker, [2] se debe
  ejecutar..."`) — exactamente el patrón que el prompt de sistema marca como ejemplo incorrecto, y
  que la sesión 5 solo había visto en la pregunta de control (hallazgo 13), no en la pregunta
  relevante.

Conclusión: "la mejor citación de toda la investigación" (hallazgo 12) no se reprodujo tal cual al
pasar de `llama-cli` a la API HTTP con `repeat_penalty` agregado a mano — hace falta más ajuste de
sampling y una re-validación real antes de confiar en esa medición para producción. Consistente con
la advertencia que ya dejaba el hallazgo 7 (sesión 2): el comportamiento aislado no siempre predice
el comportamiento embebido, y acá ni siquiera hizo falta cambiar de modelo para verlo — alcanzó con
cambiar de interfaz (CLI vs API HTTP) sobre el mismo binario y el mismo GGUF.

### Hallazgo 21: viabilidad concreta con Spring AI — wiring resuelto, ajuste de sampling pendiente

Investigado puntualmente si `spring-ai-starter-model-openai` (en vez de
`spring-ai-starter-model-ollama`, que es lo que usa el proyecto hoy) podría hablarle a este
contenedor:

- **Transporte**: `OpenAiChatModel` apuntando a `spring.ai.openai.base-url=http://llama-server:8080`
  es un patrón estándar para servidores compatibles con OpenAI como `llama-server` — no hace falta
  nada especial de este lado.
- **Salida estructurada**: `ChatClient.entity(..., spec -> spec.useProviderStructuredOutput())`
  contra un proveedor OpenAI arma exactamente el `response_format: json_schema` que el hallazgo 19
  ya probó que funciona.
- **El parámetro `repeat_penalty` del hallazgo 20** no es parte de la API oficial de OpenAI, así que
  `OpenAiChatOptions` no tiene un campo propio para él — pero Spring AI expone
  `OpenAiChatOptions.builder().extraBody(Map.of("repeat_penalty", 1.1))` para mandar parámetros no
  estándar a servidores compatibles con OpenAI (vLLM, Ollama, `llama-server`), que cubre exactamente
  este caso.
- **Cambios de código necesarios** (punto 3 de la lista de arquitectura del ADR-0009): cambiar la
  dependencia Maven, reemplazar `OllamaChatOptions` por `OpenAiChatOptions` en los tres componentes
  (`PlanificadorOllama`, `VerificadorGroundingOllama`, `SintetizadorOllama`), y **eliminar** las
  llamadas a `enableThinking()`/`disableThinking()` — Bonsai no tiene modo thinking, esa rama de
  código no aplicaría. Alcance acotado, consistente con lo que ya estimaba el ADR.

**Conclusión de la sesión 6**: el wiring con Spring AI es viable y con menos fricción de la que el
ADR-0009 anticipaba (probablemente sin gramáticas GBNF). Pero la calidad de síntesis medida en la
sesión 5 no se reprodujo automáticamente al pasar de `llama-cli` a la API HTTP real — apareció un
modo de falla nuevo (repetición) y, ya corregido, una regresión de citación frente a la propia
medición de la sesión 5. Ninguno de los dos hallazgos cambia la decisión de ADR-0009 (sigue
pospuesta), pero sí cambian el trabajo pendiente si se retoma: el riesgo de arquitectura bajó
(puntos 1 y probablemente 4), pero se suma una tarea de ajuste de sampling y re-validación de
calidad que antes no estaba identificada.

Estado del entorno al cierre: contenedor `kb-llama-server` (imagen `base-conocimiento-llama-server`)
sigue arriba en la rama `worktree-experimento+bonsai-llama-server`, sirviendo el mismo GGUF de la
sesión 5. `Dockerfile.bonsai`, `compose.bonsai.yml` y `entrypoint-bonsai.sh` quedaron comiteados
solo en esa rama — `main`, `compose.yml` y el cliente de Spring AI real no se tocaron.

## Sesión 7: buscando un sucesor a `Bonsai-8B` — `Ternary-Bonsai-8B` no es una mejora neta

Disparada por la pregunta directa "¿hay un reemplazo superior al Bonsai que ya está integrado?".
A diferencia de las sesiones 1-6 (que solo comparaban candidatos entre sí), esta sesión arrancó con
WebSearch para verificar si el panorama de PrismML había cambiado desde marzo/abril 2026 (cuando se
escribió el ADR-0009), y solo después bajó a probar el candidato más prometedor contra el pipeline
real, con el mismo protocolo de las sesiones 5-6 (`/v1/chat/completions`, `response_format`
`json_schema`, los mismos casos canónicos del ADR-0008).

**Nota de proceso**: el primer research (vía sub-agente) reportó URLs y números de PR de GitHub
específicos — antes de confiar en esos datos se repitieron las búsquedas clave de forma independiente
(WebSearch directo, `WebFetch` a las páginas primarias) para descartar que fueran alucinados. Todos
los datos que siguen ya pasaron por esa segunda verificación.

### Hallazgo 22: la cuantización Q1_0 (la que ya usa este proyecto) se fusionó a `llama.cpp` mainline

Confirmado en la discusión oficial [`ggml-org/llama.cpp#21417`](https://github.com/ggml-org/llama.cpp/discussions/21417):
Q1_0 (1-bit) ya está soportada en el `llama.cpp` mainline, con CUDA incluido — builds recientes de la
rama principal la corren sin el fork de PrismML. Esto **satisface parcialmente la condición de
reapertura #1 del ADR-0009** ("el fork se integra a la rama principal de llama.cpp"), aunque solo
para el `Bonsai-8B-Q1_0.gguf` que ya está en producción en esta rama, no para ningún candidato nuevo.
No se probó en esta sesión reconstruir `Dockerfile.bonsai` contra `ggml-org/llama.cpp` en vez del
fork — queda como tarea futura de bajo riesgo, no evaluada acá.

La ternaria Q2_0 (ver hallazgo 23) **no** se benefició de esta fusión: el PR de soporte CUDA
([`ggml-org/llama.cpp#25707`](https://github.com/ggml-org/llama.cpp/pull/25707)) seguía abierto, sin
mergear, al momento de esta sesión.

### Hallazgo 23: `Ternary-Bonsai-8B` no es "Bonsai entrenado en más bits" — es una cuantización ternaria de Qwen3-8B

Suposición inicial, basada en el nombre y en el anuncio de PrismML ("builds on the efficiency
frontier we began exploring with the recently released 1-bit Bonsai models"): que `Ternary-Bonsai-8B`
comparte la arquitectura nativa entrenada desde cero del `Bonsai-8B` original. Falsa. La model card en
Hugging Face lo desmiente directo: **`"Base model: Qwen3-8B"`**, con arquitectura GQA/SwiGLU/RoPE/RMSNorm
estándar — es una cuantización ternaria (1.58 bit/peso, `{-1,0,+1}`, escala FP16 cada 128 pesos) de un
modelo Qwen3 ya entrenado, no un entrenamiento nativo en baja precisión como el 1-bit original.

Esto importa porque Qwen3 es exactamente la familia que ya descartó a `qwen3:4b` en la sesión 2
(hallazgo 5: el modo "thinking" no se puede suprimir de verdad vía API, bug
[`ollama/ollama#12917`](https://github.com/ollama/ollama/issues/12917)) y que comparte causa raíz con
la falla de convergencia de `nanbeige4.1:3b`/`minicpm5` (hallazgo 7). Al arrancar el contenedor de
prueba, el log de `llama-server` lo confirmó: `init: init: chat template, thinking = 1` — el modo
thinking está activo por defecto en esta build. Motivo suficiente para tratar este candidato con la
misma sospecha que a cualquier modelo con thinking nativo, y probarlo a fondo antes de confiar en su
mejor score de benchmark (75.5 contra 70.5 del `Bonsai-8B` 1-bit, promedio de MMLU Redux, MuSR, GSM8K,
HumanEval+, IFEval, BFCLv3 — este último es function-calling, relevante para `Planificador`).

### Hallazgo 24: `Bonsai-27B` (basado en Qwen3.6-27B) descartado sin prueba — no entra en esta GPU

PrismML también lanzó, en julio 2026, una build de 27B parámetros (1-bit: 3.9 GB; ternaria: 5.9 GB),
la primera de esa escala que corre en un teléfono. Se descartó sin probar: la variante 1-bit (3.9 GB)
ya excede los ~3.3-3.9 GB libres reales medidos en la T600 (sesión 1, hallazgo 1), y la ternaria
(5.9 GB) no entra ni de cerca. Además hereda el mismo modo thinking de Qwen3.6-27B, sin validar. No
vale la pena el tiempo de descarga (varios GB) para un candidato que ya se sabe que no entra en VRAM.

### Hallazgo 25: VRAM real de `Ternary-Bonsai-8B` — mucho más ajustada que el "1.75 GB ideal" anunciado

Contenedor de prueba (`kb-llama-server-test`, mismo `Dockerfile.bonsai` ya construido, GGUF nuevo
montado aparte, puerto 8082, `kb-llama-server` original detenido para liberar VRAM y medir limpio):

| | Anunciado (PrismML) | Medido con `nvidia-smi` en la T600 |
|---|---|---|
| `Bonsai-8B` (Q1_0, actual) | 1.15 GB disco | 1753-1759 MiB (sesiones 5-6) |
| `Ternary-Bonsai-8B` (Q2_0) | 1.75 GB disco (+600 MB vs Q1_0) | **2606 MiB** (759 MiB base CUDA + 2606 = 3365 MiB totales, **solo 574 MiB libres** de los 4096 MiB de la tarjeta) |

La diferencia no es un error de medición: `llama-server` auto-detectó `n_parallel=4` (cuatro slots de
generación concurrente), multiplicando la cache KV reservada respecto a la configuración de un solo
slot que midieron las sesiones 5-6. No se probó fijar `n_parallel=1` explícito para achicar esa
reserva — queda como ajuste pendiente si se retoma este candidato. Con el margen medido acá (574 MiB),
cualquier pico de contexto más largo o carga concurrente real arriesga quedarse sin VRAM.

### Hallazgo 26: `VerificadorGrounding` — correcto en los dos casos, sin fuga de "thinking" pese al riesgo del hallazgo 23

Mismo par de casos canónicos del ADR-0008/hallazgo 14, contra `POST /v1/chat/completions` con
`response_format: json_schema` (`strict: true`), `temperature: 0`, `max_tokens: 20` (el mismo tope
que usa `VerificadorGroundingOpenAi.java` en producción):

| Caso | Esperado | Veredicto | Tokens usados |
|---|---|---|---|
| "explicame como usar Java 25" | `false` | `{"respondeLaPregunta": false}` ✅ | 17 de 20 |
| "como se despliega el servicio" | `true` | `{"respondeLaPregunta": true}` ✅ | 19 de 20 |

**El riesgo del hallazgo 23 no se materializó**: pese a que el modelo reporta `thinking=1` al cargar,
la salida JSON forzada convergió limpio dentro del presupuesto de 20 tokens, sin ningún token de
`<think>` en la respuesta. La gramática que fuerza `response_format` parece suprimir el thinking desde
el primer token, igual que ya había insinuado el hallazgo 19 (sesión 6) para el Bonsai 1-bit original.
Dato nuevo y valioso más allá de este candidato puntual: la salida JSON forzada vía `llama-server`
podría neutralizar el modo thinking incluso en modelos que sí lo tienen activo por defecto — algo que
ninguna sesión anterior había probado directamente sobre un modelo con thinking real detrás de
`response_format` (las sesiones 2 y 7-hallazgo-7 solo habían visto el thinking escapar cuando no había
gramática forzándolo, o fallar del todo).

### Hallazgo 27: `Planificador` — un caso correcto, uno peor que el `Bonsai-8B` actual

Mismo catálogo y prompt real de `PlanificadorOpenAi.java`, `max_tokens: 80`:

| Pregunta | Esperado | `Ternary-Bonsai-8B` | `Bonsai-8B` actual (sesión 6, hallazgo 19) |
|---|---|---|---|
| "como esta implementada la fusion RRF en el codigo" | con `search_code` | `["search_code"]` ✅ | `["search_code"]` ✅ |
| "como se despliega el servicio" | sin `search_code`; `search_docs`/`search_unified` | `["search_docs", "recent_commits"]` — evitó la trampa de `search_code`, pero **`recent_commits` no encaja** con una pregunta de "cómo se hace X" (esa herramienta es para "qué cambió últimamente", no para instrucciones) | `["search_docs", "search_unified"]` — limpio |

No es una falla de convergencia (hallazgo 7) ni una violación del formato (el `razon` respetó el
límite de palabras en los dos casos) — es una imprecisión de elección, la misma categoría de defecto
menor que el hallazgo 15 (sesión 5) ya había medido en el `Bonsai-8B` 1-bit para este mismo caso, pero
acá con una herramienta objetivamente peor elegida.

### Hallazgo 28: síntesis — pierde la mejor citación de la investigación, pero es el primer candidato que no alucina en la pregunta de control

Mismos cuatro fragmentos y mismo prompt de sistema real de `SintetizadorOpenAi.java`,
`repeat_penalty: 1.1`, `max_tokens: 512`.

**Pregunta relevante** ("como se despliega el servicio"): citó **los cuatro fragmentos, incluido el
`[3]` irrelevante** (la carpeta `corpus/`), pegando el contenido casi textual uno detrás de otro con
una cita al final de cada uno. Es el mismo defecto de "pegado de texto crudo" que ya había descartado
a `granite4.1:3b`, `phi4-mini:3.8b` y `qwen2.5:3b` (hallazgos 3, 8 y 9) — y pierde justo el punto
fuerte que hacía especial al `Bonsai-8B` 1-bit original: ignorar el fragmento irrelevante y citar solo
lo que respalda cada afirmación puntual (hallazgo 12).

**Pregunta de control** ("explicame como usar Java 25"): respondió *"Para usar Java 25, no hay
información específica en el contexto proporcionado sobre cómo hacerlo. [n]"* — **es el primer
candidato de los trece probados en esta investigación (los doce de las sesiones 1-5 más este) que no
reprodujo la alucinación del ADR-0008** (pegar las instrucciones de despliegue como si respondieran la
pregunta de Java). Con un defecto menor de formato: dejó el marcador `[n]` literal sin resolver a
un número real, en vez de omitir la cita por completo como pide el prompt para este caso.

Balance: mejora en el eje que llevaba trece candidatos sin resolverse (la alucinación de control), a
costa de retroceder justo en el eje donde el `Bonsai-8B` actual tenía la mejor medición de toda la
investigación (la citación en la pregunta relevante). No es una mejora neta, es un trade-off distinto.

### Hallazgo 29: procesamiento de prompt notablemente más lento que la generación

En las cuatro pruebas de esta sesión, el procesamiento del prompt corrió a **~9.6-10.6 tok/s** —
comparable o más lento que la generación (~5.0-5.2 tok/s), cuando en GPU el procesamiento de prompt
normalmente es varios órdenes de magnitud más rápido que la generación token a token. Ejemplo
concreto: 468 tokens de prompt tardaron 47 segundos solo en la etapa de prefill, antes de generar una
sola palabra de respuesta. Consistente con la advertencia ya anotada en el ADR-0009 sobre `Bonsai-8B`
1-bit (hallazgo 11): los benchmarks de velocidad de PrismML se miden en Ada Lovelace (RTX 4090), con
soporte de tensor cores para operaciones de bits bajos que Turing (T600) no tiene. No se investigó a
fondo la causa exacta (sesión de validación de candidato, no de perfilado) — pero el impacto es real:
en consultas del pipeline con contexto largo (varios fragmentos + prompt de sistema), este candidato
paga un costo de latencia adicional que el `Bonsai-8B` actual no tiene medido en la misma magnitud.

### Conclusión de la sesión 7

**`Ternary-Bonsai-8B` no es un reemplazo superior al `Bonsai-8B` que ya está integrado — es un
trade-off distinto, con una mejora real en un eje y retrocesos en otros dos.** No cambia la decisión
del ADR-0009 (la integración de un candidato *nuevo* seguiría exigiendo el mismo trabajo de
arquitectura que ya se hizo para el actual, sin ganancia neta que lo justifique). Resumen:

- **A favor**: es el único candidato de trece que no alucina en la pregunta de control fuera de
  dominio — el modo de falla que el ADR-0008 documentó primero y que ningún otro candidato había
  resuelto. También confirma (hallazgo 26) que la salida JSON forzada puede neutralizar el modo
  thinking incluso en un modelo que sí lo tiene activo, un dato reutilizable más allá de este
  candidato puntual.
- **En contra**: peor citación en la pregunta relevante (pierde el punto más fuerte medido del Bonsai
  actual), una elección de herramientas menos precisa en `Planificador`, VRAM bastante más ajustada
  (574 MiB libres contra los ~2.3 GB que deja el Bonsai actual) y un procesamiento de prompt
  notablemente más lento.
- **Hallazgo colateral que sí es accionable ya, sin cambiar de modelo**: la cuantización Q1_0 del
  `Bonsai-8B` actual se fusionó a `llama.cpp` mainline (hallazgo 22) — sería posible reconstruir
  `Dockerfile.bonsai` contra `ggml-org/llama.cpp` en vez del fork de PrismML, satisfaciendo parte de
  la condición de reapertura #1 del ADR-0009 sin tocar el modelo ni el resto del pipeline. No
  evaluado en esta sesión (build y pruebas de regresión pendientes).
- **No evaluado en esta sesión, candidatos fuera de la familia Bonsai** (investigados solo por
  research, sin prueba empírica): `Ministral 3B Instruct` (Apache 2.0, sin thinking forzado por
  defecto, soporte GGUF nativo sin fork) y `Hermes 2 Pro - Mistral 7B` (Apache 2.0, especializado en
  function-calling/JSON). Ambos eliminarían la dependencia del fork por completo, a diferencia de
  cualquier variante de Bonsai — quedan como la vía más prometedora para una sesión futura si se
  prioriza reducir el riesgo de arquitectura del ADR-0009 por encima de la calidad medida hoy.

Estado del entorno al cierre: el contenedor de prueba (`kb-llama-server-test`) se eliminó al terminar;
el `kb-llama-server` original se reinició y quedó sirviendo otra vez `Bonsai-8B-Q1_0.gguf`, sin
cambios respecto a como estaba antes de esta sesión. El GGUF de `Ternary-Bonsai-8B`
(`Ternary-Bonsai-8B-Q2_0.gguf`, 2.03 GiB) se descargó a `.data/bonsai/` para las pruebas y se borró al
cerrar. No se tocó `compose.yml`, `Dockerfile.bonsai`, `compose.bonsai.yml`, `entrypoint-bonsai.sh` ni
el cliente de Spring AI real en ningún momento de esta sesión.

## Sesión 8: `llama3.2:3b` y `gemma2:2b` contra los tres roles reales del pipeline — la sesión 4 solo había medido VRAM

Pregunta disparadora: la sesión 4 (hallazgo 9) descartó `llama3.2:3b` (3.1 GB, 75% GPU / 25% CPU) y
`gemma2:2b` (2.2 GB, 90% GPU / 10% CPU) **solo por no entrar 100% en GPU** — nunca se probó su
calidad real de síntesis, a diferencia de `qwen2.5:3b` (el único que sí llegó a esa prueba en esa
sesión). Esta sesión cierra ese hueco: los dos candidatos, contra el `Planificador`,
`VerificadorGrounding` y `Sintetizador` reales.

**Nota de metodología, distinta de las sesiones 1-4**: desde la sesión 6 (`aea5085`), el pipeline ya
no usa `OllamaChatModel` para estos tres roles — `PlanificadorOllama`/`SintetizadorOllama`/
`VerificadorGroundingOllama` no existen más en el código, reemplazados por
`PlanificadorOpenAi`/`SintetizadorOpenAi`/`VerificadorGroundingOpenAi` contra `OpenAiChatModel`
(hoy apuntando a `llama-server`/Bonsai, ver ADR-0009). Probar estos dos candidatos "como modelo de
Ollama para el pipeline" ya no es posible tal cual lo hacían las sesiones 1-4. En su lugar, se llamó
directo a la API nativa de Ollama (`POST /api/chat`, `format` con JSON Schema para salida
estructurada — el equivalente nativo de `useProviderStructuredOutput()`), reusando **verbatim** los
tres prompts de sistema reales (`PlanificadorOpenAi`/`VerificadorGroundingOpenAi`/`SintetizadorOpenAi`,
tal como están hoy en el código) y el mismo par de preguntas canónicas de siempre ("como se despliega
el servicio" / "explícame cómo usar Java 25") contra el mismo contexto de 4 fragmentos que usaron
`gemma3:4b`/`qwen2.5:3b`/Bonsai en las sesiones 2-9. El fragmento `[3]` (irrelevante a propósito) es
un sustituto equivalente-pero-no-idéntico al de esas sesiones: el README ya no trae la descripción
literal de "la carpeta `corpus/`" que citaban, porque [ADR-0011](adrs/0011-vault-unificado.md) unificó
el vault y esa sección cambió — no afecta el juicio (sigue siendo un fragmento genuinamente ajeno a
la pregunta de despliegue), pero se documenta la sustitución por transparencia.

Reiniciando `kb-ollama` en frío antes de cada modelo, igual que la sesión 4.

### Hallazgo 30: `llama3.2:3b` — Planificador y VerificadorGrounding perfectos, y la mejor citación medida en un modelo que no es Bonsai — pero inventa contenido nuevo en la pregunta de control, no solo pega texto

VRAM reproducida igual que la sesión 4: **3.1 GB, 75% GPU / 25% CPU**. Primera llamada (carga en
frío) tardó ~293 s; el resto, entre 4 y 25 s.

| Rol | Caso | Resultado | Veredicto |
|---|---|---|---|
| Planificador | "como se despliega el servicio" | `["search_docs", "search_unified"]` | ✅ limpio, sin caer en `search_code` |
| Planificador | "como esta implementada la fusion RRF en el codigo" | `["search_code"]` | ✅ |
| VerificadorGrounding | "explicame como usar Java 25" | `false` | ✅ |
| VerificadorGrounding | "como se despliega el servicio" | `true` | ✅ |
| Sintetizador | "como se despliega el servicio" | *"Se despliega el servicio copiando `.env.example` a `.env` y corriendo `docker compose up -d` [2]."* | ✅ cita única, bien pegada al final — pero solo cubre el fragmento `[2]`, sin mencionar Docker Desktop/WSL2 (`[1]`) ni el auto-detect de GPU (`[4]`): correcto pero incompleto |
| Sintetizador | "explicame como usar Java 25" | *"Para usar Java 25, se necesita tener instalado el entorno de desarrollo Java 25 y compilarlo... Se recomienda consultar la documentación oficial de Oracle o utilizar Maven o Gradle..."* | ❌ alucina, y de una forma nueva: no *pega* el texto del contexto (el defecto ya catalogado de `granite4.1:3b`/`phi4-mini`/`qwen2.5:3b`) sino que **inventa contenido genérico que no está en ningún fragmento**, pese a reconocer primero que "no hay información específica en este contexto" |

Es, de los candidatos NO-Bonsai probados en toda la investigación, el primero con Planificador y
VerificadorGrounding perfectos en los cuatro casos canónicos a la vez, y una citación de la pregunta
relevante tan limpia como la mejor medida hasta ahora (hallazgo 12, Bonsai). Pero la pregunta de
control expone un modo de falla nuevo: alucinación por invención de conocimiento propio en vez de
pegado de contexto — la misma categoría de violación del prompt de `SintetizadorOpenAi` ("Respondes
SOLO con lo que aparece en el contexto... si no alcanza, dilo en vez de inventar"), pero con un
mecanismo distinto al ya catalogado.

### Hallazgo 31: `gemma2:2b` — misma alucinación universal de control, y un falso negativo nuevo de `VerificadorGrounding` sobre la pregunta relevante

VRAM reproducida igual que la sesión 4: **2.2 GB, 90% GPU / 10% CPU**. Primera llamada ~191 s; el
resto, entre 11 y 19 s — el candidato más liviano y rápido de los dos.

| Rol | Caso | Resultado | Veredicto |
|---|---|---|---|
| Planificador | "como se despliega el servicio" | `["search_docs", "search_unified"]` | ✅ |
| Planificador | "como esta implementada la fusion RRF en el codigo" | `["search_code"]` | ✅ |
| VerificadorGrounding | "explicame como usar Java 25" | `false` | ✅ |
| VerificadorGrounding | "como se despliega el servicio" | **`false`** | ❌ falso negativo: la pregunta relevante se rechaza igual que la de control |
| Sintetizador | "como se despliega el servicio" | *"Para desplegar el servicio se necesita Docker Desktop con el motor iniciado [1]."* | ✅ correcto y bien citado, pero aún más incompleto que `llama3.2:3b` (ni `[2]` ni `[4]`) |
| Sintetizador | "explicame como usar Java 25" | *"Para desplegar el servicio se necesita Docker Desktop con el motor iniciado. En Windows, además hay que copiar `wslconfig.example`... [1]"* | ❌ reproduce la alucinación universal del ADR-0008 (pega las instrucciones de despliegue como respuesta a Java 25) |

El hallazgo del `VerificadorGrounding` es el más importante de los dos: en el pipeline real
(`UmbralRelevancia` habilitado), un veredicto `false` sobre "como se despliega el servicio" —
justo la pregunta que ADR-0008 usa como caso de control positivo — significaría rechazarla con el
mensaje fijo de "sin información" pese a que el contexto correcto sí estaba disponible. Es el mismo
tipo de fragilidad que el ADR-0008 ya documentó como no-determinismo de `VerificadorGrounding` con
`gemma3:4b` (resuelto ahí bajando `temperature` a 0) — aquí la llamada ya se hizo con
`temperature: 0`, así que no es un problema de muestreo: es un juicio equivocado y reproducible de
este modelo puntual sobre este caso.

### Conclusión de la sesión 8

Ninguno de los dos resuelve el problema central de la investigación (la alucinación de control del
ADR-0008): **ambos la reproducen**, cada uno con una variante distinta (invención de contenido nuevo
en `llama3.2:3b`; pegado del contexto real pero fuera de tema en `gemma2:2b`, el patrón ya conocido).
Ninguno mejora sobre Bonsai en ese eje (hallazgo 28: Bonsai sigue siendo, de trece candidatos
probados en total, el único que no alucina en la pregunta de control).

Sí aportan dato nuevo sobre los otros dos roles:

- **`llama3.2:3b`** tiene el mejor Planificador + VerificadorGrounding medido de cualquier candidato
  NO-Bonsai — cero fallos en los cuatro casos canónicos. Si el objetivo fuera separar roles (un
  modelo liviano para planificar/verificar, Bonsai u otro solo para sintetizar), es el candidato más
  prometedor de los dos para esos dos roles puntuales.
- **`gemma2:2b`** es más liviano y más rápido, pero su falso negativo de `VerificadorGrounding` sobre
  una pregunta genuinamente relevante es una falla más grave en la práctica que "alucina en la
  pregunta de control" — ese segundo problema ya lo comparten doce candidatos de trece; el primero
  bloquearía respuestas correctas en producción.

**Limitación de esta sesión, a diferencia de las 1-7**: no se cableó ningún candidato en el `kb-api`
real. La prueba fue directa contra la API nativa de Ollama (`/api/chat` + `format`), no contra el
`response_format: json_schema` de la API compatible con OpenAI que de verdad usa
`OpenAiChatModel`/`useProviderStructuredOutput()` en el código hoy. Ollama expone un endpoint
compatible con OpenAI (`/v1/chat/completions`) que en teoría permitiría apuntar
`SPRING_AI_OPENAI_BASE_URL` ahí sin tocar `PlanificadorOpenAi`/`VerificadorGroundingOpenAi`/
`SintetizadorOpenAi`, pero su soporte real de `response_format: json_schema` con `strict: true` no
se verificó en esta sesión — queda como paso pendiente antes de considerar cablear cualquiera de
los dos en el pipeline real.

Estado del entorno al cierre: `kb-ollama` quedó con `gemma2:2b` cargado (90%/10%, `keep_alive` 1h
por defecto). No se tocó `.env`, `compose.bonsai.yml` ni ningún archivo de configuración real —
las pruebas fueron llamadas HTTP puntuales contra el puerto ya expuesto de Ollama
(`localhost:11434`), sin crear ni modificar ningún contenedor.

## Sesión 10: `Ministral 3 3B Instruct-2512` contra el pipeline real end-to-end — el mejor resultado combinado de toda la investigación, con un costo de VRAM más alto que Bonsai

Disparada por un research previo (no una sesión de prueba, solo WebSearch/WebFetch) que identificó a
`Ministral 3 3B Instruct-2512` (Mistral AI) como el único candidato pendiente que cumplía en teoría
los tres filtros que ningún reemplazo de Bonsai había logrado combinar: licencia Apache 2.0
confirmada en su model card de Hugging Face, sin modo "thinking" por defecto (existe una variante
`-Reasoning` aparte), y GGUF oficial (`mistralai/Ministral-3-3B-Instruct-2512-GGUF`, `Q4_K_M`,
2.15 GB) que corre en `llama.cpp` mainline sin necesitar el fork de PrismML que usa Bonsai.

**Nota de proceso, distinta de las sesiones 5-9**: esta vez no hubo que compilar nada. La imagen
oficial `ghcr.io/ggml-org/llama.cpp:server-cuda` ya trae soporte para este modelo — se usó tal cual,
con el modelo descargado en caliente vía el flag `-hf` de `llama-server` (que resuelve el repo de
Hugging Face solo). El contenedor de prueba corrió en el puerto 8082, sin tocar
`compose.bonsai.yml`. Al llegar a la prueba de los tres roles, en vez de pegar los prompts de
sistema a mano (como sesiones 5 y 8), se levantó un `kb-api-test` real (`docker compose run`, solo
`compose.yml`, sin `compose.gpu.yml` ni `compose.bonsai.yml`) con `SPRING_AI_OPENAI_BASE_URL`
apuntando al contenedor de Ministral vía `host.docker.internal` — así el pipeline real
(`Orquestador` con sus 7 etapas, retrieval real contra el corpus real ya ingerido con `jls25.pdf`,
`VerificadorGrounding` con la puerta de relevancia en su valor de producción) corrió sin ningún
atajo, la primera vez que un candidato nuevo (no-Bonsai) se prueba así en esta investigación.

### Hallazgo 32: VRAM en idle — más del doble que Bonsai, y muy sensible a dos flags que no son obvios por defecto

Primera medición, con los defaults de `llama-server`: **3463 MiB usados, solo 476 MiB libres** de
los 4096 MiB de la T600 — peor incluso que `Ternary-Bonsai-8B` (574 MiB libres, hallazgo 25). Causa,
igual que en ese hallazgo: `llama-server` auto-detectó `n_slots = 4` (cuatro slots de generación
concurrente, cada uno con su propia cache KV) y además cargó de forma automática el componente de
visión (`mmproj`, el modelo es multimodal — 3.4B de lenguaje + 0.4B de encoder de imagen) que este
pipeline no usa.

Con `--parallel 1 --no-mmproj` (el caso real de uso: una sola consulta a la vez, texto puro):
**2603 MiB usados, 1336-1344 MiB libres**, estable antes y después de correr los tres roles del
pipeline real (hallazgo 34). Sigue siendo **más del doble que Bonsai** (1753 MiB), pero deja margen
cómodo — muy por encima del límite ajustado que dejaba `Ternary-Bonsai-8B`.

| Modelo | VRAM real | Libres de 4096 MiB |
|---|---|---|
| `Bonsai-8B` (1-bit) | 1753 MiB | ~2.3 GB |
| `Ministral 3 3B Instruct-2512` (Q4_K_M, 1 slot, sin mmproj) | 2603 MiB | ~1.3 GB |
| `Ternary-Bonsai-8B` (Q2_0, 4 slots default) | 3365 MiB | 574 MiB |

### Hallazgo 33: velocidad de generación — la más rápida de cualquier candidato de baja huella probado hasta ahora

Con un prompt de 543 tokens (plantilla de chat + una pregunta trivial), a 98% de utilización de GPU:
**9.2 tok/s de generación**. Más rápido que `Bonsai-8B` (5.8-6.9 tok/s, sesiones 5-6) y que
`Ternary-Bonsai-8B` (~5.0-5.2 tok/s, sesión 7) — consistente con que `Q4_K_M` es un formato de
cuantización mucho mejor soportado en Turing (T600) que los kernels de 1-bit/ternarios, que dependen
de tensor cores que esta GPU no tiene (ver hallazgo 11 y 29).

El procesamiento de prompt, en cambio, salió **igual de lento que la generación** (9.8 tok/s,
543 tokens en 55s) — el mismo patrón anómalo que la sesión 7 ya había medido en
`Ternary-Bonsai-8B` (hallazgo 29), y que ninguna sesión con `Bonsai-8B` había cuantificado
directamente. No se investigó la causa exacta (fuera del alcance de una sesión de validación de
candidato) — pero el efecto práctico es real: con los prompts largos que arma
`Orquestador.construirContexto()` (varios fragmentos numerados + el prompt de sistema), el prefill
pesa tanto como la generación en el total de la latencia.

### Hallazgo 34: los tres roles reales, con la puerta de relevancia en su valor de producción — el mejor resultado combinado de los quince candidatos probados

A diferencia de toda sesión anterior (que probaba los prompts de sistema reales pero pegados a mano,
o con la puerta de relevancia apagada a propósito para aislar la síntesis), esta prueba corrió
`POST /api/ask` contra el pipeline completo, sin ningún atajo, con
`KB_UMBRAL_RELEVANCIA_HABILITADO=true` (el default de producción, no overrideado). El corpus real ya
incluye `jls25.pdf` (desde la sesión 9/ADR-0011), así que "explícame cómo usar Java 25" ya no es un
caso 100% fuera de dominio como en las sesiones 1-8 — hay contenido genuinamente relacionado
ingerido. Se mantuvo la pregunta de todas formas por continuidad con el resto de la investigación, y
se agregó el caso de `search_code` de las sesiones 5-9 para cubrir los tres roles con los tres tipos
de pregunta.

| Caso | Plan del Planificador | VerificadorGrounding (resultado observable) | Síntesis |
|---|---|---|---|
| "como se despliega el servicio" | `["search_unified"]`, razón "pregunta sobre despliegue (requisitos, configuración, entorno)" — limpio, sin `search_code` | Dejó pasar (hubo respuesta con citas) | Citó `despliegue.md` con los pasos correctos, **y explícitamente descartó el fragmento irrelevante de `jls25.pdf`** ("No hay información relevante en los documentos sobre *jls25.pdf* para responder sobre cómo se despliega el servicio") en vez de citarlo por citar — comportamiento nuevo, ningún candidato anterior lo había hecho. Incompleto: no mencionó Docker Desktop/WSL2, que estaba en el mismo fragmento citado |
| "explícame cómo usar Java 25" | `["search_docs", "search_unified"]`, razón "pregunta conceptual sobre lenguaje/framework (Java 25)" | Rechazó | `MENSAJE_SIN_INFORMACION`, 0 citas — igual que el resultado protegido que el hallazgo 16 midió con `gemma3:4b` |
| "como esta implementada la fusion RRF en el codigo" | `["search_code", "search_unified"]`, razón "petición concreta sobre implementación de algoritmo (RRF) en código fuente del proyecto" — exacto | Rechazó (repo vacío en este entorno, sin candidatos que pasar) | `MENSAJE_SIN_INFORMACION`, esperado dado que no hay repo indexado en este entorno |

Los tres casos del Planificador salieron limpios — ninguna herramienta de más, ninguna trampa
código-vs-documentación, razones bien formadas dentro del límite de palabras. Es, junto con
`llama3.2:3b` (sesión 8), uno de los dos candidatos NO-Bonsai con Planificador perfecto en todos los
casos probados, pero el único que además tiene los tres veredictos de `VerificadorGrounding`
correctos **verificados con la puerta real de producción activada**, no con los dos casos aislados
que usaban las sesiones 5-9. No se pudo reproducir el defecto de "alucinación de control" del
ADR-0008 en esta sesión porque la puerta lo interceptó antes de llegar a síntesis — igual que ya
había pasado con `gemma3:4b` (hallazgo 16) y, por inferencia, con Bonsai (hallazgo 14).

Latencia: 177s (pregunta con síntesis completa), 141s (control, rechazado tras planificador +
verificador), 17s (código, rechazo rápido por falta de contenido) — más lento que Bonsai en total
pese a la generación más rápida (hallazgo 33), consistente con el prefill lento del hallazgo 33
pesando sobre el total en preguntas con contexto largo.

### Conclusión de la sesión 10

**`Ministral 3 3B Instruct-2512` es el primer candidato de quince que combina Planificador correcto,
`VerificadorGrounding` correcto con la puerta real activada, y síntesis bien citada — sin depender
del fork de PrismML.** No es un reemplazo automático de Bonsai: es un trade-off distinto, más claro
que el de `Ternary-Bonsai-8B` (sesión 7).

- **A favor**: los tres roles pasaron limpio contra el pipeline real, no contra una réplica aislada
  de los prompts. Generación más rápida que cualquier variante de Bonsai (hallazgo 33). Sin
  dependencia del fork — corre en la imagen oficial de `llama.cpp`, más simple de mantener que
  `Dockerfile.bonsai`. Primer candidato en descartar explícitamente un fragmento irrelevante en la
  síntesis en vez de citarlo por citar (hallazgo 34).
- **En contra**: usa más del doble de VRAM que Bonsai (2603 MiB contra 1753 MiB, hallazgo 32) — sigue
  entrando cómodo en la T600, pero con menos margen. Prefill tan lento como la generación
  (hallazgo 33), un costo que Bonsai no tiene medido en la misma magnitud. Síntesis incompleta en el
  caso relevante (omitió parte del fragmento citado). Y el caso de control ("Java 25") ya no es una
  prueba limpia de alucinación fuera de dominio en este corpus — solo confirma que la puerta de
  relevancia sigue funcionando, no que el modelo resista la tentación de alucinar como sí lo probó
  el hallazgo 13 con Bonsai (puerta apagada).

**Recomendación**: amerita quedar como el candidato de referencia si en algún momento se prioriza
eliminar la dependencia del fork de PrismML sobre el margen de VRAM — es la primera vez en diez
sesiones que un candidato sin fork pasa los tres roles reales sin fallar ninguno. No desplaza a
Bonsai como recomendación de producción por sí solo: la ganancia (sin fork, más rápido generando) se
paga con VRAM y con una pregunta de control que ya no mide lo mismo que antes en este corpus. Antes
de una decisión real, faltaría repetir el caso de control con una pregunta genuinamente fuera de
dominio para este corpus (algo que ni "despliegue" ni "Java" cubran, dado que `jls25.pdf` ahora
domina el vault), y medir la latencia con contexto largo real (no solo los tres casos puntuales de
esta sesión) antes de comparar el costo total por consulta contra Bonsai.

Estado del entorno al cierre: los contenedores temporales (`kb-ministral-test`, `kb-api-test`) se
eliminaron al terminar. `kb-llama-server` (Bonsai) se había detenido para medir VRAM limpia
(protocolo de la sesión 7, hallazgo 25) y se restauró al cerrar
(`docker compose -f compose.yml -f compose.gpu.yml -f compose.bonsai.yml up -d`), verificado de
vuelta en 1753 MiB. Nota operativa repetida de la sesión 4: levantar el `kb-api-test` con
`docker compose run` usando solo `compose.yml` (sin `compose.gpu.yml`) volvió a recrear `kb-ollama`
sin la reserva de GPU — no afectó esta sesión porque los embeddings ya corren en CPU
(`bge-m3-cpu`), pero se corrigió igual al restaurar el stack completo con los tres archivos compose.
No se tocó `compose.bonsai.yml`, `.env` ni el cliente de Spring AI real en ningún momento de esta
sesión — el trabajo en curso de la sesión 9 (piloto de 100 preguntas, `eval-100-preguntas/`, cambios
sin commitear en `PlanificadorOpenAi.java`/`VerificadorGroundingOpenAi.java`/`compose.bonsai.yml`)
se dejó exactamente como estaba, sin retomar ni revertir.

## Sesión 11: `Ternary-Bonsai-8B-Q2_0_g64` (mainline, sin fork) — mismo trade-off que el `_g128` del fork, con un prefill mucho más caro

Segunda pista que había dejado pendiente el research previo a la sesión 10: la sesión 7 (hallazgo 25)
midió `Ternary-Bonsai-8B` con el `_g128` del fork de PrismML en 574 MiB libres, con la sospecha de
que el problema fuera la agrupación de 128 pesos, no el modelo en sí. Para cuando se escribió esa
sesión, el soporte CUDA de la variante `_g64` (adoptada como estándar en mainline por costar "menos
de 6% de memoria adicional" según el propio mantenedor de `llama.cpp`) todavía no estaba mergeado.
Esta sesión repite la medición con `_g64` ya disponible en la imagen oficial
`ghcr.io/ggml-org/llama.cpp:server-cuda`, sin el fork, mismo protocolo que la sesión 10 (contenedor
temporal, `--parallel 1 --no-mmproj`, `kb-api-test` real contra `POST /api/ask` con la puerta de
relevancia activada).

Archivo confirmado contra la fuente primaria (listado de archivos del repo en Hugging Face):
`prism-ml/Ternary-Bonsai-8B-gguf/Ternary-Bonsai-8B-Q2_0_g64.gguf`, 2.31 GB (el `_g128`/sin sufijo de
la sesión 7 pesaba 2.03-2.18 GB — confirma en la práctica el "6% mas" que anticipaba el mantenedor).

### Hallazgo 35: VRAM — mejor que el `_g128` con 4 slots, pero peor que Ministral

Con 1 slot y sin mmproj (no aplica multimodal a este modelo, pero se dejó el flag por paridad con la
sesión 10): **2769-2781 MiB usados, 1158-1170 MiB libres** de los 4096 MiB de la T600. Mejor que la
medición de la sesión 7 (574 MiB libres), pero esa comparación no es limpia: la sesión 7 medía con
4 slots automáticos (multiplicando la cache KV), no 1. Con la misma configuración de 1 slot, queda
peor que `Ministral 3 3B` (1336-1344 MiB libres, hallazgo 32) y sigue siendo el segundo candidato más
ajustado de VRAM de toda la investigación, detrás de `openbmb/minicpm5` (100% GPU pero descartado por
otras razones) y por delante del `_g128` con 4 slots.

| Modelo | VRAM real (1 slot) | Libres de 4096 MiB |
|---|---|---|
| `Bonsai-8B` (1-bit) | 1753 MiB | ~2.3 GB |
| `Ministral 3 3B Instruct-2512` (Q4_K_M) | 2603 MiB | ~1.3 GB |
| `Ternary-Bonsai-8B-Q2_0_g64` (mainline) | 2769-2781 MiB | ~1.15 GB |

### Hallazgo 36: generación comparable al `_g128`, pero el prefill en frío es dramáticamente más lento — y se corrige solo con el kernel ya "caliente"

Primera llamada tras cargar el modelo: **1.14 tok/s de prefill** (21 tokens, 18.4 segundos) contra
**5.48 tok/s de generación** — una brecha nunca vista en esta investigación, ni siquiera en el
hallazgo 29 (sesión 7, `_g128` del fork: prefill 9.6-10.6 tok/s, del mismo orden que la generación).
Repetir la llamada inmediatamente después (mismo contenedor, sin reiniciar) subió el prefill a
**6.67 tok/s** — del mismo orden que la generación (5.85 tok/s esa segunda vez), consistente con que
la primera llamada paga una compilación/selección de kernel CUDA en frío que las siguientes ya no
pagan. **No confirmado si esto es específico de Turing** (la duda que dejaba abierta el research
previo a esta sesión sobre soporte de sm_75 para los kernels ternarios de mainline): no se probó en
otra arquitectura, pero el patrón (JIT de kernel en la primera invocación) es consistente con un
camino de kernel genérico sin especializar para GPUs antiguas, a diferencia de Ministral (`Q4_K_M`,
formato mucho más maduro en llama.cpp) que no mostró esta brecha en su primera llamada (hallazgo 33).

Dato práctico para el pipeline real: la primera consulta después de levantar el contenedor paga este
costo de calentamiento en la primera de sus 2-3 llamadas secuenciales (Planificador, luego
VerificadorGrounding/Sintetizador) — no se aisló cuánto costó exactamente dentro de la latencia total
medida en el hallazgo 37, pero es coherente con que la primera pregunta de esta sesión (309s) haya
sido más lenta que las siguientes dos (285s, 19s) pese a tener menos contenido que sintetizar que la
segunda.

### Hallazgo 37: los tres roles reales — Planificador y VerificadorGrounding perfectos, síntesis correcta pero incompleta, y una latencia total muy por encima de Ministral y Bonsai

Mismos tres casos canónicos que la sesión 10, mismo protocolo (`POST /api/ask` real, puerta de
relevancia activada):

| Caso | Plan del Planificador | VerificadorGrounding (resultado observable) | Síntesis | Latencia |
|---|---|---|---|---|
| "como se despliega el servicio" | `["search_docs", "search_unified"]`, razón "pregunta de despliegue, no de codigo" | Dejó pasar | *"Para desplegar el servicio se necesita Docker Desktop con el motor iniciado. En Windows, además hay que copiar wslconfig.example a .wslconfig para darle memoria a WSL2 [1]."* — correcto y bien citado, pero **incompleto**: no menciona `.env.example`/`.env` ni `docker compose up -d`, pese a que ambos vienen del mismo fragmento `[1]` | 310s |
| "explícame cómo usar Java 25" | `["search_docs", "search_unified"]`, razón "pregunta conceptual, no de implementación" | Rechazó | `MENSAJE_SIN_INFORMACION` | 286s |
| "como esta implementada la fusion RRF en el codigo" | `["search_code", "search_unified"]`, razón "pregunta sobre implementación específica" | Rechazó (repo vacío en este entorno) | `MENSAJE_SIN_INFORMACION` | 20s |

Los tres planes salieron limpios, igual que `Ministral 3 3B` y `llama3.2:3b` — tercer candidato
NO-Bonsai con Planificador perfecto en los casos probados. `VerificadorGrounding` también acertó los
tres, con la puerta real activada, igual que Ministral. La diferencia frente a Ministral está en dos
ejes: la síntesis cubre menos del fragmento citado (Ministral, en cambio, citó bien lo que cubrió y
además descartó explícitamente el fragmento irrelevante — hallazgo 34), y sobre todo la **latencia**:
310s y 286s contra los 177s y 141s de Ministral para los mismos dos casos — un candidato bastante más
lento pese a tener una VRAM libre menor.

### Conclusión de la sesión 11

**`Ternary-Bonsai-8B-Q2_0_g64` en mainline resuelve el problema de dependencia del fork que tenía la
variante de la sesión 7, pero no mejora sobre `Ministral 3 3B` en ningún eje medido — VRAM más
ajustada, síntesis menos completa, y latencia notablemente mayor.** Sí sigue superando a Bonsai en un
punto que ya había medido la sesión 7 y esta confirma: el Planificador y el `VerificadorGrounding`
funcionan limpio con la puerta real, sin la fragilidad de convergencia que mostraban otros candidatos
con "thinking" nativo (hallazgo 7).

- **A favor**: elimina la dependencia del fork de PrismML sin perder los aciertos de Planificador
  y `VerificadorGrounding` que ya tenía el `_g128` (hallazgo 26-27 de la sesión 7). VRAM razonable
  (~1.15 GB libres), mejor que el peor caso medido con el fork.
- **En contra**: el prefill en frío es el más caro medido en toda la investigación (aunque se
  normaliza tras la primera llamada). La latencia total de una consulta real (286-310s) es la más
  alta de los tres candidatos con evidencia de pipeline completo (Bonsai, Ministral, este). La
  síntesis, aunque bien citada, cubre menos contenido del fragmento relevante que Ministral.

**Recomendación**: con las dos pistas del research ya evaluadas (Ministral en la sesión 10, esta
variante en la sesión 11), la conclusión de la sesión 7 sigue de pie sin ambigüedad: **ningún
candidato de los dieciséis probados en once sesiones desplaza a Bonsai como recomendación de
producción**. Si en algún momento se prioriza eliminar la dependencia del fork sobre todo lo demás,
`Ministral 3 3B` (sesión 10) es la opción estrictamente mejor entre los dos candidatos sin fork
evaluados — gana en VRAM, en cobertura de la síntesis y en latencia frente a esta variante de
`Ternary-Bonsai-8B`. No quedan pistas concretas sin evaluar de la lista que dejó el research previo a
la sesión 10.

Estado del entorno al cierre: los contenedores temporales (`kb-ternary-test`, `kb-api-test`) se
eliminaron al terminar. `kb-llama-server` (Bonsai) se había detenido para medir VRAM limpia y se
restauró al cerrar (`docker compose -f compose.yml -f compose.gpu.yml -f compose.bonsai.yml up -d`),
verificado de vuelta en 1753 MiB. Mismo efecto colateral que la sesión 10 con `kb-ollama` perdiendo
la reserva de GPU al levantar `kb-api-test` con solo `compose.yml` — corregido igual al restaurar el
stack completo. No se tocó `compose.bonsai.yml`, `.env` ni el trabajo en curso de la sesión 9
(`eval-100-preguntas/`) en ningún momento de esta sesión.

## Sesión 12: Bonsai real de producción, por primera vez bajo el mismo protocolo riguroso que Ministral y Ternary-Bonsai — filtra su propio prompt de sistema en la respuesta

Pregunta disparadora, del usuario: si Ministral generó más rápido que Bonsai (hallazgo 33) y pasó los
tres roles limpio (hallazgo 34), ¿por qué la sesión 10 seguía recomendando a Bonsai? La respuesta
honesta: nunca se había sometido a Bonsai al mismo protocolo riguroso (`POST /api/ask` real, puerta
de relevancia activada, contra el corpus real) que sí se usó con Ministral (sesión 10) y
`Ternary-Bonsai-8B` (sesión 11). La evidencia a favor de Bonsai databa de las sesiones 5-6: prompts
pegados a mano contra `llama-cli`, con la puerta de relevancia **apagada** a propósito para aislar la
síntesis — una comparación despareja. Esta sesión corrige eso: los mismos tres casos canónicos,
contra el `kb-api` real de producción (puerto 8080, sin contenedores temporales — Bonsai ya es el
modelo real corriendo), sin overridear nada.

### Hallazgo 38: velocidad real — Bonsai es el más lento de los tres, no el más rápido

Medido en caliente contra el puerto real de `llama-server` (8081): **6.65 tok/s de generación, 8.95
tok/s de prefill**. Confirma el hallazgo 33 de la sesión 10: **Ministral genera más rápido que
Bonsai** (9.2 tok/s contra 6.65 tok/s) — la sesión 10 ya lo había medido así, pero la conclusión final
no lo puso en primer plano. VRAM real: 1753-1765 MiB usados, 2174-2186 MiB libres — sigue siendo la
huella más chica de los tres candidatos con evidencia de pipeline completo.

| Modelo | Generación | Prefill | VRAM libre |
|---|---|---|---|
| `Ministral 3 3B` | 9.2 tok/s | ~10 tok/s (calentado) | ~1.3 GB |
| `Bonsai-8B` (1-bit) | 6.65 tok/s | 8.95 tok/s | ~2.2 GB |
| `Ternary-Bonsai-8B` `_g64` | 5.48 tok/s | ~6.7 tok/s (calentado) | ~1.15 GB |

### Hallazgo 39: la síntesis real filtra fragmentos completos del propio prompt de sistema en la respuesta — reproducible, no un evento aislado

En la pregunta relevante ("como se despliega el servicio"), la respuesta real del `kb-api` de
producción:

> Para desplegar el servicio se necesita Docker Desktop con el motor iniciado. En Windows, además
> hay que copiar wslconfig.example a .wslconfig para darle memoria a WSL2 [1]. PEGADO
>
> Cada afirmacion debe llevar el marcador [n] de la fuente numerada en el contexto de la que sale,
> PEGADO al final de esa afirmacion puntual -- nunca antes de ella, y nunca varios marcadores sueltos
> agrupados al final del texto sin decir que frase respalda cada uno. Ejemplo correcto: "Se necesita
> Docker Desktop iniciado [2]." Ejemplo incorrecto: "Para configurar Docker, [2] se necesita..." (la
> cita antes de la afirmacion) o dejar "[1], [3]" sueltos al cierre de la respuesta.
>
> Ve directo a la respuesta. NO narres tu razonamiento ("primero voy a...", "el usuario esta
> preguntando...", "veamos el contexto..."): eso no es la respuesta, es ruido que el usuario tiene
> que leer igual. La primera palabra que escribas debe ser parte de la respuesta misma.

La palabra "PEGADO" no la inventó el modelo: es literal del prompt de sistema real de
`SintetizadorOpenAi.java` (línea 46, "...PEGADO al final de esa afirmacion puntual..."). Bonsai copió
verbatim sus propias instrucciones de sistema — los dos ejemplos de citación completos, la
instrucción anti-narración — como si fueran parte de la respuesta al usuario, **después** de haber
dado la respuesta correcta y bien citada en la primera oración.

Se repitió la misma pregunta una segunda vez para descartar que fuera ruido de muestreo
(`temperature: 0.2`, no determinística): **se repitió el mismo patrón**, con una variante al cierre
("No hay contradicciones entre las fuentes en este contexto." — otra instrucción literal del prompt,
la de contradicciones, que tampoco aplica citarla como si fuera parte de la respuesta). Es un defecto
reproducible de esta combinación puntual (Bonsai 1-bit + el prompt de sistema real, tal como quedó
después de los ajustes no documentados que ya trae `SintetizadorOpenAi.java` hoy), no un evento
aislado.

Es un modo de falla nuevo, no catalogado en ninguna de las once sesiones anteriores: ninguna de ellas
había probado la síntesis real de Bonsai con este prompt de sistema exacto contra el pipeline real
(las sesiones 5-6 usaban una versión más corta del prompt, pegada a mano, sin la puerta de
relevancia). El propio comentario de `SintetizadorOpenAi.java` (líneas 23-26) ya dejaba una
advertencia sin resolver: *"la citacion todavia midio peor que en las pruebas aisladas de la sesion
5 ... sigue pendiente mas ajuste y re-validacion, no es un problema resuelto del todo"* — esta sesión
confirma esa sospecha con un caso concreto y más grave de lo que ese comentario anticipaba (no solo
peor citación, sino filtración completa del prompt).

`Ministral 3 3B` (sesión 10) y `Ternary-Bonsai-8B _g64` (sesión 11), probados contra el mismo prompt
de sistema real bajo el mismo protocolo, **no mostraron este defecto** en ninguna de sus pruebas.

### Hallazgo 40: Planificador y VerificadorGrounding, igual de limpios que los otros dos candidatos

| Caso | Plan del Planificador | VerificadorGrounding | Latencia |
|---|---|---|---|
| "como se despliega el servicio" | `["search_docs", "search_unified"]` | Dejó pasar | 340s (1a vez) / 83s (2a vez, cache tibia) |
| "explícame cómo usar Java 25" | `["search_unified"]` | Rechazó | 276s |
| "como esta implementada la fusion RRF en el codigo" | `["search_code", "search_unified"]` | Rechazó (repo vacío) | 21s |

Sin sorpresas acá: Bonsai empata con Ministral y `Ternary-Bonsai-8B` en estos dos roles, los tres
candidatos con Planificador y VerificadorGrounding perfectos contra los tres casos canónicos. La
latencia de la pregunta relevante (340s la primera vez) es la más alta de los tres candidatos con
evidencia de pipeline completo — **peor que Ministral (177s) y que `Ternary-Bonsai-8B` (310s)**, pese
a tener la VRAM más liviana de los tres. Coherente con el hallazgo 38: menos tok/s de generación, y
acá además una respuesta más larga de lo necesario (el prompt filtrado completo se suma a los tokens
generados).

### Conclusión de la sesión 12 — cambia la recomendación de las sesiones 7-11

**Bajo el mismo protocolo riguroso, `Ministral 3 3B` supera a Bonsai en los tres ejes medibles:
velocidad (9.2 contra 6.65 tok/s), latencia total de una consulta real (177s contra 340s) y calidad
de síntesis (Ministral no muestra ningún defecto; Bonsai filtra su propio prompt de sistema de forma
reproducible).** La única ventaja que le queda a Bonsai es VRAM (1753 MiB contra 2603 MiB) — real,
pero ya no suficiente por sí sola para sostener la recomendación de las sesiones 7-11, que se apoyaba
en evidencia de síntesis de las sesiones 5-6 obtenida con un prompt de sistema distinto (más corto,
sin la puerta de relevancia) al que corre hoy en producción.

Esto **no** es una prueba de que Bonsai sea inherentemente peor modelo que Ministral — es evidencia
de que la combinación actual (Bonsai 1-bit + el prompt de sistema real de hoy + `repeat_penalty: 1.1`
+ el resto de la configuración de `compose.bonsai.yml`) tiene un defecto concreto y reproducible que
ninguna sesión anterior había medido, porque ninguna había probado exactamente esta combinación. Es
posible que un ajuste de sampling (el mismo tipo de ajuste que ya resolvió la repetición completa de
respuesta en la sesión 6) o una poda del prompt de sistema reduzcan o eliminen la filtración — no se
probó en esta sesión, que fue de medición, no de corrección.

**Recomendación**: la elección entre Bonsai y Ministral ya no es obvia a favor de Bonsai. Antes de
decidir un cambio de producción, faltaría: (a) intentar el mismo tipo de ajuste de sampling/prompt
que corrigió la repetición de la sesión 6 para ver si la filtración de Bonsai es corregible sin
cambiar de modelo, y (b) correr varias repeticiones más de cada candidato (esta sesión corrió cada
caso una sola vez, salvo la relevante con Bonsai) para descartar que cualquiera de las dos
observaciones sea ruido de muestreo. Hasta entonces, la comparación más honesta es: **Ministral 3 3B
es hoy el candidato con mejor evidencia medida bajo el protocolo más riguroso de toda la
investigación — más rápido, sin fork, y sin el defecto que sí se encontró en Bonsai** — pero
reemplazar el modelo de producción por eso significaría perder margen de VRAM y aceptar una
integración sin las ~12 sesiones de rodaje que ya tiene Bonsai.

Estado del entorno al cierre: esta sesión no usó ningún contenedor temporal — las pruebas fueron
directas contra `kb-api` (puerto 8080) y `kb-llama-server` (puerto 8081) reales de producción, sin
crear, detener ni modificar ningún contenedor. No se tocó `compose.bonsai.yml`, `.env`,
`SintetizadorOpenAi.java` ni ningún archivo de configuración real. El trabajo en curso de la
sesión 9 (`eval-100-preguntas/`) se dejó exactamente como estaba.

## Sesión 13: diagnóstico y ajuste de sampling para la filtración del prompt (hallazgo 39) — resuelve el defecto grave, deja uno menor sin resolver

Intento de arreglar, sin cambiar de modelo, el defecto que encontró la sesión 12: Bonsai filtraba
fragmentos completos de su propio prompt de sistema en la respuesta real. Metodología: iterar contra
`llama-server` directo (puerto 8081, sin pasar por Spring/rebuild en cada intento) con un contexto de
6 fragmentos reconstruido a mano (idéntico en formato a `Orquestador.construirContexto()`, con los
mismos textos que devolvió la recuperación real en las sesiones 10-12) hasta encontrar una
combinación que se sostuviera, y recién ahí aplicarla a `SintetizadorOpenAi.java`, reconstruir la
imagen y re-validar contra `POST /api/ask` real.

### Hallazgo 41: la causa más probable es la ventana de `repeat_penalty` (`repeat_last_n`), no el modelo en sí

Hipótesis de partida: `repeat_penalty` de `llama-server` solo penaliza repetir tokens que aparecieron
en los últimos `repeat_last_n` tokens del contexto (default de `llama.cpp`: 64). El prompt de sistema
real mide varios cientos de tokens, y el contexto completo de una consulta real (6 fragmentos,
`jls25.pdf` incluido) llega a **1386 tokens de prompt** en la reconstrucción de esta sesión. Con la
ventana por defecto, el modelo puede "copiar" texto del prompt de sistema sin que `repeat_penalty` lo
penalice, porque para cuando genera esa parte de la respuesta esos tokens ya quedaron fuera de la
ventana de 64.

Confirmado en la práctica: con un contexto corto (3 fragmentos, sin el ruido de `jls25.pdf`) el
defecto **no se reprodujo** con la configuración actual (`repeat_penalty: 1.1` solo). Con el contexto
completo de 6 fragmentos (1386 tokens) sí se reprodujo, aunque con una variante distinta a la del
hallazgo 39: esta vez copió el encabezado de cita del fragmento `[1]` (`"[1] despliegue.md
(file:///...)"`) en vez de un fragmento del prompt de sistema — mismo patrón de fondo (copiar texto
lejano del contexto en vez de generar), disparado por el mismo mecanismo.

### Hallazgo 42: `repeat_last_n: -1` (penalizar contra el contexto completo) reduce la fuga pero no la elimina sola

Agregando `repeat_last_n: -1` al `extraBody` (semántica de `llama.cpp`: `-1` = penalizar contra todo
el contexto, no solo los últimos N tokens) contra el contexto completo de 6 fragmentos: la respuesta
pasó a cubrir los cuatro pasos de despliegue completos, con una sola cita `[1]` bien puesta — pero
todavía terminaba con la palabra suelta "pegado" (ya no "PEGADO": ver hallazgo 43) al final. Mejora
real, no total.

### Hallazgo 43: la palabra en mayúsculas del prompt ("PEGADO") es un gatillo aparte del problema de ventana — bajarla a minúscula lo saca de la respuesta

Con `repeat_last_n: -1` fijo, subir `repeat_penalty` a 1.3 no eliminó la fuga: la empujó a un modo de
falla peor (alucinó pasos de despliegue incorrectos y generó una lista degenerada de "[n] N / PEGADOS"
para los seis fragmentos) — la penalización excesiva empuja al modelo lejos de tokens válidos, un
efecto conocido de sobre-ajustar `repeat_penalty`. Se probó en cambio bajar la palabra "PEGADO" (la
única palabra en mayúsculas de énfasis del prompt de sistema, en la instrucción de citación) a
minúscula ("pegado"), manteniendo `repeat_penalty: 1.1` + `repeat_last_n: -1`: **la fuga desapareció
por completo**, en dos repeticiones consecutivas contra el contexto completo. La hipótesis: el
mayúsculas actuaba como un token inusualmente "pegajoso" para este modelo cuantizado a 1-bit — no se
investigó la causa exacta a nivel de tokenización (fuera del alcance de una sesión de ajuste, no de
perfilado interno del modelo).

### Hallazgo 44: `presencePenalty` fue necesario para sostener el arreglo con el prompt de sistema completo — pero mueve la citación, no solo la fuga

Con la palabra en minúscula y `repeat_last_n: -1` solos, la fuga volvió a aparecer de forma
intermitente al variar el contexto reconstruido. Agregando `presencePenalty` (parte del contrato
estándar de OpenAI, no necesita `extraBody`) sí la sostuvo, pero el valor importa mucho:

| `presencePenalty` | Fuga | Citación |
|---|---|---|
| 0.3 | Ninguna en 2 pruebas | **Desapareció por completo** — cero marcadores `[n]` en la respuesta |
| 0.2 | Ninguna | Presente, pero el modelo empezó a comentar los fragmentos irrelevantes de `jls25.pdf` uno por uno en vez de ignorarlos |
| 0.1 | Ninguna en 4 pruebas | Presente, pero inconsistente: a veces una cita `[1]` bien puesta, a veces varias por paso, una vez un marcador `[n]` sin resolver a número real |

Se eligió **0.1**, el valor más bajo que ya sostenía la ausencia de fuga: por encima de eso, el
costo (perder las citas del todo a 0.3, o divagar sobre contenido irrelevante a 0.2) superaba el
beneficio.

### Hallazgo 45: aplicado a `SintetizadorOpenAi.java` real y verificado contra `POST /api/ask` — la fuga no volvió a aparecer, la citación sigue con el mismo problema que ya tenía documentado el propio código

Cambios aplicados: "PEGADO" → "pegado" en el prompt de sistema, `repeat_last_n: -1` agregado al
`extraBody` existente (junto a `repeat_penalty: 1.1`), y `.presencePenalty(0.1)` en las opciones del
`ChatClient`. Imagen reconstruida y `kb-api` real reiniciado con el cambio.

| Caso | Resultado | Latencia |
|---|---|---|
| "como se despliega el servicio" (1a vez) | Cobertura completa de los 4 pasos, una cita `[1]` al final, sin fuga | 72s |
| "como se despliega el servicio" (2a vez) | Cobertura parcial (solo pasos 2-3), marcador `[n]` sin resolver a número, sin fuga | 45s |
| "explícame cómo usar Java 25" | `MENSAJE_SIN_INFORMACION` (correcto) | 36s |
| "como esta implementada la fusion RRF en el codigo" | `MENSAJE_SIN_INFORMACION` (correcto, repo vacío) | 23s |

**La fuga del hallazgo 39 no volvió a aparecer en ninguna de las cuatro llamadas reales** (dos de
ellas la misma pregunta relevante, para cubrir la variabilidad de `temperature: 0.2`). Planificador y
`VerificadorGrounding` siguen perfectos, sin cambios — el ajuste solo tocó `SintetizadorOpenAi`.

**Lo que no se arregló**: la calidad de citación sigue siendo inconsistente entre corridas (a veces
completa y bien puesta, a veces parcial con un marcador sin resolver). Esto **no es un defecto nuevo
de esta sesión** — es el mismo problema que el propio comentario de `SintetizadorOpenAi.java` ya
dejaba anotado antes de esta sesión: *"la citacion todavia midio peor que en las pruebas aisladas de
la sesion 5 ... sigue pendiente mas ajuste y re-validacion, no es un problema resuelto del todo"*. El
ajuste de esta sesión resolvió el defecto **nuevo y más grave** que encontró la sesión 12 (filtración
completa de texto ajeno a la respuesta), no el defecto **preexistente y menor** de citación.

**Latencia**: mejora notable y consistente frente a la sesión 12 (340s/276s/21s sin el ajuste,
72-45s/36s/23s con él) — la ausencia de fuga también significa menos tokens generados por respuesta
(el texto filtrado sumaba varios cientos de tokens), no solo mejor calidad.

### Conclusión de la sesión 13

**El ajuste de sampling sí mejoró a Bonsai de forma real y medible: elimina el defecto grave de la
sesión 12 (filtración del prompt de sistema) y reduce la latencia de una consulta real entre 3 y 5
veces, sin costo de VRAM (el cambio es puramente de sampling, no de modelo).** No lo deja perfecto:
el defecto de citación inconsistente que ya traía documentado el código sigue presente,
aproximadamente al mismo nivel que antes de esta sesión — no empeoró, pero tampoco se resolvió.

Con este resultado, la comparación contra `Ministral 3 3B` (sesión 10) cambia otra vez:

| | Bonsai (sesión 12, sin ajustar) | Bonsai (esta sesión, ajustado) | Ministral 3 3B (sesión 10) |
|---|---|---|---|
| Filtración de prompt | Sí, reproducible | No, en 4/4 pruebas | No |
| Citación | Filtrada junto con el resto | Inconsistente (completa a veces, parcial otras) | Completa, pero a veces incompleta en cobertura |
| Latencia (pregunta relevante) | 340s | 45-72s | 177s |
| VRAM libre | ~2.2 GB | ~2.2 GB (sin cambio) | ~1.3 GB |

Bonsai ajustado ya no pierde contra Ministral en latencia (queda más rápido) ni en VRAM (sigue
ganando), y el defecto grave que motivó la sesión 12 quedó resuelto. La citación de ninguno de los
dos es perfecta — es un defecto compartido en distinta forma, no un punto a favor claro de ninguno.

**Recomendación**: el ajuste "funcionó" para el objetivo puntual de esta sesión (arreglar la
filtración), así que **no hace falta migrar a Ministral por ese motivo** — la razón que motivaba el
cambio ya no aplica. Queda como decisión abierta, no técnica sino de producto: ¿la citación
inconsistente que persiste en Bonsai (documentada desde antes de esta investigación, nunca resuelta
en ninguna sesión) es tolerable, o amerita seguir buscando una solución de fondo (salida estructurada
para la síntesis, la vía que ya sugería la sesión 3, en vez de más ajuste de sampling en texto
libre)? No se decidió en esta sesión.

Estado del entorno al cierre: **a diferencia de todas las sesiones 5-12, esta sí modificó el pipeline
real** — `SintetizadorOpenAi.java` quedó con el prompt corregido y las tres opciones nuevas
(`repeat_last_n`, `presencePenalty`), la imagen `base-conocimiento-api` se reconstruyó dos veces
(una con `presencePenalty(0.3)`, descartada; la final con `0.1`), y `kb-api` real quedó corriendo la
versión con el ajuste. Ningún archivo de compose ni `.env` cambió. El cambio está sin commitear,
igual que el resto del trabajo en curso de la sesión 9 (`eval-100-preguntas/`,
`PlanificadorOpenAi.java`, `VerificadorGroundingOpenAi.java`, `compose.bonsai.yml`), que se dejó
exactamente como estaba.

## Sesión 14: el piloto de 100 preguntas retomado — un bug de portabilidad en el ajuste de la sesión 13, y la primera comparación de precisión con datos reales (no solo latencia)

Continuación directa del piloto de evaluación que había quedado en 18/100 (sesión 9, sin cerrar
formalmente en este documento — sus cambios seguían sin commitear en `compose.bonsai.yml`,
`PlanificadorOpenAi.java` y `VerificadorGroundingOpenAi.java`). El pedido de esta sesión fue puntual:
terminar ese piloto. En el camino aparecieron dos problemas de infraestructura del harness mismo, no
del pipeline, más la primera comparación de precisión (no solo velocidad) entre Bonsai y Ministral
con una muestra real.

### Hallazgo 46: `repeat_last_n: -1` (el arreglo de la sesión 13) rompe contra el build oficial de `llama.cpp` que sirve a cualquier candidato sin el fork de PrismML

Al correr el mismo piloto contra `Ministral 3 3B` (para comparar de igual a igual con Bonsai bajo la
misma carga de preguntas), casi todas las respuestas volvían vacías sin marcarse como error. La causa:
`repeat_last_n: -1` (agregado en la sesión 13 para arreglar la filtración de prompt de Bonsai) es un
valor que el fork de PrismML acepta con la semántica de `llama.cpp` ("-1 = todo el contexto"), pero
el build oficial `ghcr.io/ggml-org/llama.cpp:server-cuda` (el que sirve a Ministral y a cualquier
candidato sin fork) lo **rechaza con 400**: `Field 'repeat_last_n': Value must be between 0 <= value
<= 2147483647, but got -1`. Spring AI absorbía ese 400 como una excepción no capturada en el flujo de
streaming, y el turno quedaba vacío en la UI sin pasar por la rama de error que sí detecta
`ejecutar.js` (`turno-estado.error`).

Arreglo: cambiar `repeat_last_n` de `-1` a `4096` (un valor positivo que empata con `BONSAI_CTX_SIZE`
y logra el mismo efecto práctico — penalizar contra todo el contexto real de esta configuración — sin
depender de la semántica especial de `-1` que solo el fork acepta). Verificado con una llamada directa
contra `llama-server` de Ministral (`repeat_last_n: 4096` sí funciona) y luego contra `kb-api` real de
Bonsai (sigue sin la filtración del hallazgo 39, la latencia de la pregunta canónica no cambió). Este
hallazgo es importante más allá de esta sesión: cualquier ajuste de sampling que se agregue a
`SintetizadorOpenAi.java` pensando solo en Bonsai puede no ser portable al día que se integre un
candidato sin fork (Ministral u otro) — vale la pena probar contra los dos backends antes de dar un
ajuste por cerrado.

### Hallazgo 47: el harness de Playwright (`ejecutar.js`) tenía dos bugs de resiliencia que no se habían visto en corridas cortas

Con una corrida larga (~100 preguntas seguidas, sesiones de horas) aparecieron dos modos de falla que
las corridas cortas de prueba nunca habían expuesto:

- **Loop de crasheos sin relanzar.** `chrome-headless-shell.exe` puede colapsar (`Target crashed`) a
  mitad de una corrida. El chequeo `browser.isConnected()` al inicio de cada iteración no detecta
  este caso -- el proceso principal de Chromium sigue "conectado" pero corrupto, y
  `browser.newPage()` falla siempre igual. En una corrida real esto encadenó **62 preguntas seguidas
  falladas** (ids 39-100 de una corrida) sin que nada relanzara el navegador. Arreglo: detectar el
  patrón del mensaje de error (`crashed|Target closed|Protocol error`) dentro del `catch` y forzar un
  `browser.close()` + relanzamiento ahí mismo, no depender del chequeo de la siguiente iteración.
- **El relanzamiento mismo puede fallar, y sin protección tumba todo el proceso.** Con el primer
  arreglo aplicado, un `chromium.launch()` que también fallara (medido en vivo: una interrupción de
  la sesión de terminal a mitad de una corrida mató procesos huérfanos de Chromium; otra vez, presión
  real de memoria del sistema con `vmmemWSL` + Docker + el resto de aplicaciones dejando menos de
  4 GB libres de 33 GB totales hizo que `chrome-headless-shell.exe` muriera con `exitCode=3221225794`
  -- 0xC0000005, violación de acceso -- incluso en el reintento) escapaba sin capturar y mataba
  `main()` completo, dejando docenas de preguntas pendientes sin correr. Pasó dos veces en esta sesión
  (una con 47 preguntas pendientes, otra con 25). Arreglo final: varios intentos de relanzamiento con
  espera creciente (5 s, 15 s, 30 s) en vez de uno solo, dándole tiempo a que la presión de memoria
  del sistema baje antes de rendirse.

Ninguno de los dos bugs es del pipeline ni de ningún modelo -- son del script de evaluación
(`eval-100-preguntas/ejecutar.js`), que ya venía con soporte de reanudación (salta las preguntas que
ya tienen resultado), lo que permitió retomar cada corte sin perder el trabajo previo.

### Hallazgo 48: comparación de precisión con datos reales -- empate exacto en las mismas preguntas, Ministral sistemáticamente más rápido

Bonsai nunca llegó a correr las 100 preguntas completas en esta ni en ninguna sesión anterior (se
quedó en 9, a un ritmo de ~250 s/pregunta que hubiera tomado 6-7 horas para el lote completo -- una
decisión consciente de esta sesión, confirmada con el usuario, de no gastar ese tiempo si ya se sabía
que era lento). En cambio, `Ministral 3 3B` sí completó el piloto entero: **100/100 corridas, 97
calificadas** (3 con el error de desborde de contexto del hallazgo 49) -- primera vez en toda la
investigación que un candidato corre el piloto completo de principio a fin.

Comparando las mismas 9 preguntas exactas que Bonsai alcanzó a correr (8 comparables, excluyendo el
id=2 que dio error de infraestructura en ambos por igual):

| id | Esperado | Bonsai (comportamiento/latencia) | Ministral (comportamiento/latencia) |
|---|---|---|---|
| 1 | responde | responde ✅ / 346.2s | responde ✅ / 23.7s |
| 3 | responde | responde ✅ / 297.1s | responde ✅ / 175.1s |
| 4 | responde | rechaza ❌ / 33.8s | rechaza ❌ / 21.4s |
| 5 | responde | responde ✅ / 310.6s | responde ✅ / 176.4s |
| 6 | responde | responde ✅ / 277.4s | responde ✅ / 140.0s |
| 7 | responde | rechaza ❌ / 29.7s | rechaza ❌ / 25.7s |
| 8 | responde | responde ✅ / 408.3s | responde ✅ / 227.2s |
| 9 | responde | responde ✅ / 502.2s | responde ✅ / 262.1s |

**Empate exacto en precisión (6/8 cada uno) y en el patrón de fallas** -- las mismas dos preguntas
(4 y 7) fallan igual en ambos modelos, con el mismo tipo de error (rechazo de más). Esto es un dato
valioso por sí solo: sugiere que esas dos fallas no son un problema de calidad de síntesis de ningún
modelo puntual, sino de la etapa de recuperación/umbral compartida por ambos (mismo `kb-api`, mismo
corpus, misma configuración de `KB_UMBRAL_RELEVANCIA_TECHO_CONFIANZA`) -- consistente con el
`hallazgo` (sesión 9, sin numerar formalmente en este doc) de que `jls25.pdf` domina el corpus y
puede dejar contenido relevante sin competir por espacio en el contexto final.

En las 8 preguntas comparables, **Ministral fue más rápido en las 8 sin excepción**, entre 1.4x
(pregunta 4: 33.8s vs 21.4s) y 14.6x (pregunta 1: 346.2s vs 23.7s) más rápido según el caso. Tiempo
promedio de las 9: Bonsai 247.6s, Ministral 190.4s en esas mismas 9 (incluyendo el error de la
pregunta 2 en ambos casos).

### Hallazgo 49: precisión de Ministral en la muestra completa -- 76.3%, con fallas concentradas en un patrón de rechazo, no de alucinación

Reporte completo (`npm run reporte`, cruzando con `query_log`): **74/97 correctas (76.3%)**, 3
preguntas excluidas por el mismo error de desborde de contexto que ya documentó el hallazgo 39 de
sesiones anteriores (`request (N tokens) exceeds the available context size (4096 tokens)`) -- un
límite compartido de la infraestructura (`BONSAI_CTX_SIZE`/`ctx-size` de Ministral, ambos en 4096),
no un defecto de ningún modelo puntual. Consistente con lo medido en el subconjunto de 8 preguntas
del hallazgo 48: casi todas las fallas observadas durante la corrida fueron **rechazos de preguntas
que sí debían responderse** (el patrón "AMBIGUO cae por debajo del umbral pese a tener contenido
relevante", ya documentado en sesiones anteriores), no alucinaciones de contenido inventado. No se
hizo en esta sesión un análisis pregunta por pregunta de las ~23 fallas restantes (fuera del piloto
de terminar la corrida) -- el `reporte.html` generado (`eval-100-preguntas/reporte.ministral-3-3b-100preguntas.html`, filtrable
por categoría y por "solo incorrectas") queda disponible para ese análisis en una sesión futura.

### Conclusión de la sesión 14

**No hay evidencia, con esta muestra, de que Bonsai sintetice con más precisión que Ministral --
empatan exacto en las preguntas comparables, y Ministral es sistemáticamente más rápido.** Esto no
reabre ni cierra la recomendación de producción por sí solo (la sesión 13 ya había señalado que la
ventaja de Bonsai se redujo a VRAM tras el arreglo del prompt), pero sí es la primera vez que existe
un dato de precisión sobre una muestra real de 97 preguntas técnicas (no solo los 2-3 casos canónicos
del ADR-0008 que usaban las sesiones 5-13) para respaldar la comparación.

Limitación explícita de esta comparación: la muestra pareja entre los dos modelos es de solo 8
preguntas (Bonsai nunca corrió más) -- suficiente para ver que empatan y que Ministral es más rápido,
insuficiente para una afirmación fuerte de "misma precisión" a la escala de 100 preguntas. El dato de
97 preguntas de Ministral es sólido por sí mismo, pero no tiene una contraparte de Bonsai a esa
escala por el costo de tiempo que implicaría (6-7 horas estimadas).

Estado del entorno al cierre: los contenedores temporales (`kb-api-test`, `kb-ministral-test`) se
eliminaron al terminar; `kb-llama-server` (Bonsai) se restauró y quedó de vuelta como el `kb-api` real
de producción, verificado sano. `eval-100-preguntas/ejecutar.js` quedó con los dos arreglos de
resiliencia del hallazgo 47 (sin commitear, igual que el resto del trabajo de la sesión 9).
`src/main/java/co/g3a/baseconocimiento/llm/SintetizadorOpenAi.java` quedó con `repeat_last_n: 4096`
en vez de `-1` (hallazgo 46) -- este cambio sí se aplicó sobre el archivo que la sesión 13 ya había
dejado sin commitear, reconstruido y verificado contra el `kb-api` real. Los archivos de resultados
crudos de cada corte de esta sesión (`resultados-brutos.bonsai-parcial-9preguntas.json`,
`resultados-brutos.ministral-parcial-9preguntas.json`,
`resultados-brutos.previo-config-vieja.18preguntas.json`) quedaron en `eval-100-preguntas/` junto al
resultado final, ya renombrado para distinguirlo con claridad de cualquier otro modelo que se pruebe
después (`resultados-brutos.ministral-3-3b-100preguntas.json`,
`resultados-completos.ministral-3-3b-100preguntas.json`,
`reporte.ministral-3-3b-100preguntas.html`), todos sin commitear.

## Sesión 15: por qué Ministral rechazó de más — desborde de contexto silencioso, no solo juicio del modelo, y un perfil de compose por modelo

Disparada por una pregunta directa del usuario sobre el resultado de la sesión 14: de las 26
preguntas que el piloto de 100 preguntas de Ministral falló, ¿hay forma de que el modelo responda
correctamente, o es su límite? Se investigaron tres caminos a la vez — subir `techo-confianza` para
que menos preguntas dependan de `VerificadorGrounding`, afinar su prompt para Ministral en concreto,
y probar a Bonsai en ese único rol mientras Ministral sigue de planificador/sintetizador — y se dejó
un perfil de compose independiente por modelo para no repetir el problema de fondo que esta misma
sesión encontró (ver hallazgo 52).

### Hallazgo 50: desglose real de las 26 fallas — casi todas son rechazos, no alucinaciones

Cruzando `resultados-completos.ministral-3-3b-100preguntas.json` directamente (no solo el resumen
del hallazgo 49):

| Categoría | Cantidad |
|---|---|
| `INSUFICIENTE` (el reranker nunca encontró contenido por encima del umbral) | 6 |
| `AMBIGUO`, rechazo indebido (`VerificadorGrounding` dijo `false` debiendo decir `true`) | 16 |
| Error de infraestructura (desconexión, ver hallazgo 47) | 3 |
| `AMBIGUO`, aceptación indebida (`VerificadorGrounding` dijo `true` debiendo decir `false`) | 1 |

Los 6 `INSUFICIENTE` nunca llegan a `VerificadorGrounding` — el reranker no encontró nada por encima
del piso, es un problema de recuperación, no de síntesis ni de juicio del modelo. El resto de esta
sesión se concentra en los 17 casos `AMBIGUO` (16 rechazos indebidos + 1 aceptación indebida): son
los únicos donde el rol de `VerificadorGrounding` — y por lo tanto la elección de modelo — importa de
verdad.

### Hallazgo 51: 6 de las 17 preguntas `AMBIGUO` (35%) ni siquiera llegan a un veredicto — desbordan el contexto y el error queda atrapado en silencio

Se reconstruyó el contexto exacto que vio `VerificadorGrounding` para las 17 preguntas (mismo
formato que `Orquestador.construirContexto()`, `KB_EXPANDIR_VECINOS=false`: `query_log.candidates` +
`chunks.text` de Postgres, sin inventar nada) y se corrió, aislado, contra ambos backends
(`llama-server` de Ministral y de Bonsai, mismo prompt de sistema y mismas opciones de sampling que
`VerificadorGroundingOpenAi.java`). Seis de las diecisiete —
`¿Qué es un tipo genérico parametrizado...?`, `¿Qué significa que una variable esté sombreada...?`,
`¿Un record puede implementar interfaces?`, `¿Qué es un patrón de registro...?`,
`¿Se pueden usar patrones no nombrados...?`, `¿Qué es un parámetro de tipo acotado...?` — superan los
4096 tokens de `ctx-size` solo con el prompt de sistema más el contexto, **antes de generar una sola
palabra**: `llama-server` responde `400 exceeds the available context size` para ambos modelos por
igual (confirmado con `/tokenize`, sin gastar una llamada de generación, y reproducido en vivo contra
el pipeline real con `kb-api-test` apuntando a Ministral).

Ese 400 no se ve en ningún reporte porque `VerificadorGroundingOpenAi.verificar()` atrapa *cualquier*
excepción y responde `Veredicto(false)` por precaución (ver el comentario del propio archivo: "este
verificador es la última defensa... arriesgar una alucinación es peor que negarse cuando no se pudo
verificar") — **una decisión de diseño razonable para una falla real de red, pero que aquí esconde
un desborde de contexto estructural detrás de un mensaje idéntico al de un rechazo legítimo**. Se
confirmó en vivo contra el pipeline real (`kb-api-test` con `SPRING_AI_OPENAI_BASE_URL` apuntando a
un contenedor de Ministral, mismos valores de `compose.bonsai.yml`): el log de la aplicación muestra
exactamente `Fallo al verificar grounding, se rechaza por precaucion: com.openai.errors.OpenAIIoException:
Request failed` para una de estas seis preguntas, sin ningún otro síntoma visible para quien mira el
reporte de aciertos/fallas.

**Es, con diferencia, la causa individual más grande de las 16 preguntas rechazadas de más** — más
grande que cualquier diferencia de juicio entre modelos (hallazgo 53). No es un límite de Ministral
ni de Bonsai: ambos comparten el mismo `ctx-size=4096` (perfil Bonsai) y el mismo corpus dominado por
`jls25.pdf` (sesión 9). Es un límite de presupuesto de contexto que ninguna sesión anterior había
medido específicamente para la llamada de `VerificadorGrounding` (las sesiones 8-9 sólo lo midieron
para la síntesis).

### Hallazgo 52: subir `techo-confianza` (Camino 1) recupera con seguridad solo 1 de las 17, no 3 como sugería el score crudo

Ordenando las 16 preguntas rechazadas de más por su `mejorRerank`, tres saltan a la vista como
candidatas a saltarse `VerificadorGrounding` con un `techo-confianza` más bajo que el 6.0 heredado de
Bonsai: `5.05`, `4.35` y `4.34`. Cruzado contra las preguntas de control que sí deben rechazarse (la
más alta mide `2.63`, "¿Qué es WSL2?"), un techo de ~3.0 separaría los dos grupos con margen — **pero
dos de esas tres preguntas de score alto (`4.35` y `4.34`) son exactamente dos de las seis que
desbordan el contexto (hallazgo 51)**. Saltarse `VerificadorGrounding` no las arregla: solo mueve el
punto de falla a la síntesis, que **no atrapa ese mismo error** (a diferencia del verificador) — el
hallazgo 49 ya había documentado ese patrón exacto (3 preguntas del piloto cayendo con
"se perdió la conexión con el servidor"). El resultado neto de bajar el techo sería cambiar un
rechazo prolijo por un error duro para esas dos preguntas — peor experiencia, no mejor.

Recuperable de verdad, con evidencia razonable (no probado directo en la etapa de síntesis): **una
sola pregunta** de las 17 (`¿Cómo se declara un método genérico independiente de si la clase que lo
contiene es genérica?`, `mejorRerank=5.05`, contexto de 3200-3235 tokens, dentro del presupuesto).

### Hallazgo 53: Bonsai como `VerificadorGrounding` (Camino 3) da un resultado mixto — gana 1, pierde 1, empata en 7 de 9 casos comparables

De las 11 preguntas `AMBIGUO` que sí caben en el contexto (hallazgo 51), se le pidió veredicto a
Ministral y a Bonsai por separado, mismo prompt de sistema, mismo contexto real:

| Pregunta (resumen) | Esperado | Ministral | Bonsai | Resultado |
|---|---|---|---|---|
| overload vs override | responde | false | **true** | Bonsai acierta, Ministral no |
| static en un miembro de clase | responde | false | false | ninguno acierta |
| record vs clase tradicional | responde | true | true | ambos aciertan |
| record puede extender otra clase | responde | true¹ | false | Ministral acertaría si no fuera por el hallazgo 54 |
| arrays covariantes | responde | false | false | ninguno acierta |
| causa encadenada de una excepción | responde | false | false | ninguno acierta |
| wildcard `? extends` | responde | **true** | false | Ministral acierta, Bonsai no |
| método genérico independiente de la clase | responde | false | false | ninguno acierta |
| archivos de código fuente compactos (Java 25) | responde | false | false | ninguno acierta |
| cuerpos de constructor flexibles | responde | false¹ | false | ninguno acierta |
| Java vs Python (pregunta de control, debía rechazar) | **rechaza** | true (mal) | **false (bien)** | Bonsai acierta, Ministral no |

¹ Veredicto real tras el ajuste del hallazgo 54 (con `maxTokens=20`, la respuesta de Ministral
truncaba a un JSON incompleto y el catch la convertía en rechazo igual).

Sobre las 9 preguntas donde de verdad se compara juicio contra juicio (excluyendo la del hallazgo 54,
que es un bug de presupuesto de tokens, no de criterio): **Bonsai acierta una que Ministral no
(overload/override), Ministral acierta una que Bonsai no (wildcard), y ambos coinciden — para bien o
para mal — en las 7 restantes.** Contando también el caso de control (donde Bonsai sí acierta y
Ministral no), el saldo neto de cambiar solo el modelo de `VerificadorGrounding` a Bonsai es **+1
sobre 11 casos comparables** — una mejora real pero marginal, y con un costo de latencia que no es
gratis: en los casos medidos, la llamada de Bonsai tardó entre 1x y más de 20x lo que tardó Ministral
para la misma pregunta y el mismo contexto (ej. la pregunta de wildcard: 144s Ministral vs 300s
Bonsai). **No hay evidencia, con esta muestra, de que cambiar de modelo en este rol puntual sea una
mejora que justifique la complejidad de correr dos modelos a la vez** — el hallazgo 51 (desborde de
contexto) sigue siendo la palanca más grande, y no depende de qué modelo verifique.

No se completó el Camino 2 (afinar el prompt de `VerificadorGrounding` específicamente para
Ministral) más allá de esta comparación: con solo 2 de 9 casos movidos por la elección de modelo, y
sin un patrón claro que un ajuste de prompt puntual pudiera explotar (las 7 preguntas donde ambos
modelos coinciden no comparten un rasgo obvio más allá de ser conceptos densos del JLS), no hay
evidencia de que valga la pena un tercer intento de prompt-tuning — consistente con la lección ya
anotada en la sesión 3 sobre rendimientos decrecientes de parchar prompts caso por caso.

### Hallazgo 54: `maxTokens=20` de `VerificadorGroundingOpenAi` truncaba la salida de Ministral en 3 de 17 llamadas — corregido a 40

El formato de salida JSON de Ministral agrega espacios y saltos de línea que Bonsai no usa
(`{\n  "respondeLaPregunta": true\n}` contra `{"respondeLaPregunta": true}`) — con el tope de 20
tokens que ya traía el código (pensado para el formato compacto de Bonsai), 3 de las 17 llamadas a
Ministral truncaron a mitad de un JSON válido (`{ "respondeLaPregunta": true` sin cerrar). El mismo
catch del hallazgo 51 interpreta ese fallo de parseo como una excepción más y rechaza por precaución
— indistinguible, otra vez, de un rechazo real. Reintentado con más margen (`maxTokens=40`, solo para
diagnóstico), dos de las tres mantienen el mismo veredicto que ya tenían truncado (`false`, un
rechazo genuino del modelo) y una lo cambia (`true` — la pregunta sobre si un record puede extender
otra clase, que Ministral sí sabía responder). Se subió `maxTokens` de 20 a 40 en
`VerificadorGroundingOpenAi.java`: es una llamada de clasificación, el costo de 20 tokens más es
insignificante, y da margen para el formato de cualquier modelo sin apostar por el más compacto.

### Conclusión de la sesión 15

Ninguno de los tres caminos es "el arreglo" — cada uno mueve un número chico y distinto de las 16
preguntas rechazadas de más, y juntos no cubren ni la mitad:

| Causa de las 16 fallas `AMBIGUO`→rechazo | Cantidad | ¿Arreglable, y cómo? |
|---|---|---|
| Desborda el contexto (hallazgo 51) | 6 | Sí, en principio — pero ninguna vía barata probada todavía (ver abajo) |
| `maxTokens` insuficiente para el formato de Ministral (hallazgo 54) | 1 | Sí, ya corregido en esta sesión |
| Bonsai como verificador acierta donde Ministral no (hallazgo 53) | 1 | Sí, a costa de latencia — mejora marginal |
| Ninguno de los dos modelos coincide con "debía responder" | 7 | No identificado — puede ser un problema de recuperación (¿el fragmento que sí respondería quedó afuera del top-6?) o de qué tan estricto es el criterio de "responde", no de qué modelo juzga |

**La pregunta original del usuario — ¿es el límite del modelo? — tiene una respuesta más precisa que
un sí o un no: para 8 de las 16 preguntas (desborde + bug de tokens), la causa es infraestructura, no
el modelo; para 1, cambiar de modelo ayuda pero con un costo real; para las 7 restantes, no hay
evidencia todavía de que sea ni lo uno ni lo otro** — haría falta mirar si el fragmento correcto de
`jls25.pdf` de verdad compite por un lugar en el top-6 antes de culpar al juicio del verificador
(mismo patrón que la sesión 9 ya había encontrado para la síntesis, con el corpus dominado por un
solo documento).

**Vía de mayor impacto pendiente**: el desborde de contexto (hallazgo 51) es la causa individual más
grande y comparte causa raíz con los 3 errores de infraestructura del hallazgo 49 (sesión 14). De las
tres formas de atacarlo, esta sesión resolvió la más barata:

### Hallazgo 55: `VerificadorGroundingOpenAi` ahora distingue el desborde de contexto de un rechazo real

Se agregó `esDesbordeDeContexto(Throwable)`: recorre la cadena de causas de la excepción buscando el
mensaje de `llama-server` ("exceeds the available context size" / `exceed_context_size_error`) en vez
de asumir un solo tipo de excepción — necesario porque, según la carga del servidor, el mismo error
real de `llama-server` llegó envuelto de dos formas distintas en las pruebas de esta sesión: como
`com.openai.errors.BadRequestException` (400 limpio, el caso normal) o como
`com.openai.errors.OpenAIIoException` genérico cuando hubo contención de red al medir en paralelo dos
modelos a la vez. El veredicto sigue siendo `false` — sigue siendo la opción segura, esto no cambia el
comportamiento — pero el log ahora dice la causa real en vez de "se rechaza por precaución" a secas.

Validado en vivo, no solo en la teoría: se reconstruyó la imagen de `kb-api` con el cambio y se le
mandó una de las seis preguntas del hallazgo 51 contra el `kb-llama-server` (Bonsai) real, en un
contenedor de prueba aparte (`kb-api-test-verificacion`, eliminado al terminar, sin tocar el `kb-api`
de producción). El log mostró exactamente el mensaje nuevo:

```
VerificadorGrounding no pudo evaluar: el contexto (sistema+pregunta+fragmentos) desborda
el ctx-size del modelo. Se rechaza por precaucion, pero esto NO es un juicio del modelo
sobre el contenido: com.openai.errors.BadRequestException: 400: request (4719 tokens)
exceeds the available context size (4096 tokens), try increasing it
```

**Quedan pendientes las otras dos vías, de mayor esfuerzo**: subir `BONSAI_CTX_SIZE`/`ctx-size` de
4096, o achicar el presupuesto de contexto específico de `VerificadorGrounding` (no necesita ver los 6
fragmentos completos para decidir si "alguno" responde — un resumen más corto por fragmento podría
alcanzar, pero exige tocar `Orquestador.construirContexto()` para que arme un contexto distinto según
el rol, hoy comparte el mismo texto entre verificación y síntesis, sin implementar todavía).

### Hallazgo 56: subir `ctx-size` es barato para Bonsai, ajustado para Ministral — medido en vivo con `nvidia-smi`, no asumido

Con `kb-llama-server` (producción) detenido un momento para una línea base limpia (protocolo de las
sesiones 6-7), se midió el costo real de VRAM de duplicar `ctx-size` de 4096 a 8192 para los dos
modelos, en un contenedor de prueba aparte cada vez:

| Modelo | VRAM a `ctx-size=4096` | VRAM a `ctx-size=8192` | Costo de duplicar | Libre al final (T600, ~3.9 GB reales) |
|---|---|---|---|---|
| Bonsai-8B | 1765 MiB | 2333 MiB | +568 MiB | 1.6 GB |
| Ministral 3 3B | 2595 MiB | 3015 MiB | +420 MiB | 924 MiB |

**Para Bonsai, subir el contexto es barato y seguro**: pesa poco de por sí (1.15 GB en disco), así que
incluso duplicando la cache KV sobra más de 1.5 GB libres. Se aplicó como nuevo default
(`BONSAI_CTX_SIZE=8192` en `compose.bonsai.yml`), revirtiendo la cautela de la sesión 8 (que asumía,
sin medir, que duplicar arriesgaba un OOM).

**Para Ministral, la misma duplicación deja solo 924 MiB libres** — no hay señal de OOM en esta
prueba aislada, pero es un margen mucho más ajustado que el de Bonsai, sin nada de colchón para otro
consumidor de GPU concurrente (el propio escritorio de Windows ya se lleva parte de la VRAM "nominal"
de esta T600, hallazgo 1). Con estos números sobre la mesa, el usuario decidió igual subir
`compose.ministral.yml` a `ctx-size=8192` — aceptando el margen más ajustado a cambio de cubrir el
mismo desborde que Bonsai. Si esta GPU llega a dar OOM con el perfil de Ministral activo, el punto
intermedio (`ctx-size=6144`) queda como el siguiente valor a probar antes de volver a 4096 — no
medido todavía.

Estado del entorno tras esta medición: los dos contenedores de prueba
(`kb-bonsai-ctxtest`, `kb-ministral-ctxtest`) se eliminaron al terminar cada uno; `kb-llama-server`
se detuvo y reinició dos veces (una por cada modelo medido), verificado sano y de vuelta a 1753 MiB
en cada reinicio. Autorizado explícitamente por el usuario antes de detener el contenedor de
producción, siguiendo el mismo protocolo que las sesiones 6-7 ya habían usado para medir VRAM limpia.

### Perfil por modelo: `compose.ministral.yml`

Se agregó `compose.ministral.yml`, hermano de `compose.bonsai.yml` — mismo patrón (agrega un
`llama-server` y apunta `api` a él), pero con **sus propios valores**, no los de Bonsai con el
`base-url` cambiado. Esto no es cosmético: fue exactamente el problema que esta sesión destapó al
revisar la sesión 14 — el piloto de 100 preguntas de Ministral corrió con
`KB_UMBRAL_RELEVANCIA_TECHO_CONFIANZA=6.0`, un valor que la sesión 9 había tuneado a mano *para
Bonsai*, sin ninguna evidencia propia con Ministral, simplemente porque se reusó `compose.bonsai.yml`
cambiando solo el `SPRING_AI_OPENAI_BASE_URL`. Con `compose.ministral.yml`, la próxima vez que se
compare un modelo nuevo no hace falta pisar ni adivinar el ajuste de uno ya calibrado: cada modelo
tiene su propio archivo, su propio puerto (`8083`, dejando `8081` para Bonsai), y sus propios
comentarios explicando qué valor es una calibración de *ese* modelo puntual y cuál es una restricción
física que comparten todos (ctx-size, tamaño del corpus) — ver los comentarios del propio archivo
para el detalle punto por punto, incluida la corrección del hallazgo 52 sobre `techo-confianza`.

Estado del entorno al cierre: los contenedores temporales de esta sesión (`kb-ministral-experimento`,
`kb-api-test-ministral`, un intento fallido `kb-llama-server-ministral` por contención de GPU al
correr dos contenedores de Ministral a la vez, y `kb-api-test-verificacion` para validar el hallazgo
55) se eliminaron al terminar; `kb-ollama` se recreó una vez sin querer (efecto de
`docker compose run` sobre el proyecto real) pero quedó sano, verificado. `kb-api`/`kb-llama-server`
(Bonsai, producción) no se tocaron en ningún momento, verificados sanos al cierre — incluida la
reconstrucción de imagen que usó `kb-api-test-verificacion`, que solo actualizó la imagen en disco
(`base-conocimiento-api`), no el contenedor real en ejecución. El trabajo hasta el hallazgo 54
(`compose.bonsai.yml`/`PlanificadorOpenAi.java` de la sesión 9, `compose.ministral.yml`, y el ajuste
de `maxTokens`) quedó commiteado (`3c11bd3`). El ajuste del hallazgo 55
(`esDesbordeDeContexto` en `VerificadorGroundingOpenAi.java`) es posterior a ese commit y sigue sin
commitear al cierre de esta sesión.

## Sesión 16: re-correr el piloto de 100 preguntas de Ministral bajo `compose.ministral.yml` — el desborde de contexto queda resuelto, pero un factor de confusión (el `techo-confianza` corregido) tapa la mejora en el número global

Pedido directo del usuario: verificar en vivo los ajustes de la sesión 15
(`BONSAI_CTX_SIZE`/`MINISTRAL_CTX_SIZE=8192`, `esDesbordeDeContexto` en `VerificadorGroundingOpenAi`,
`maxTokens=40`) volviendo a correr el piloto completo de 100 preguntas contra Ministral, ahora bajo su
propio `compose.ministral.yml` en vez del `compose.bonsai.yml` reusado por error que había producido
el piloto de la sesión 14. Es la primera corrida de las 100 preguntas con el perfil dedicado.

### Hallazgo 57: el desborde de contexto (hallazgo 51) queda completamente resuelto — cero ocurrencias en 100 preguntas, contra 6 de 17 antes

Se revisaron los logs completos de `kb-api` durante toda la corrida (`docker logs kb-api`, sin filtro
de fecha, contenedor recreado al arrancar el perfil Ministral) buscando cualquier rastro de
"desbordo"/"desborde"/"VerificadorGrounding"/excepción/warning: **cero coincidencias**. El contenedor
completo, de arranque a las 100 preguntas, solo tiene 105 líneas de log — las de arranque de Spring
Boot y cuatro `WARNING` genéricos de JVM sobre acceso restringido (`System.loadLibrary`), nada del
pipeline. Con `ctx-size=8192` ninguna de las 100 preguntas llegó a desbordar el presupuesto de
contexto de `VerificadorGrounding`, así que el mensaje nuevo del hallazgo 55
(`VerificadorGrounding no pudo evaluar: el contexto ... desborda el ctx-size`) no tuvo ocasión de
aparecer — no porque el código no funcione, sino porque la causa que debía distinguir ya no ocurre.
**Confirma en vivo, con datos reales y no solo en la teoría, que subir `ctx-size` (hallazgo 56) fue
suficiente para eliminar esta causa de raíz.**

### Hallazgo 58: la precisión global bajó de 76.3% a 68.0% de todos modos — no por el desborde de contexto, sino por un factor de confusión: el `techo-confianza` corregido de 6.0 a 8.0

| | Sesión 14 (`ctx-size=4096`, `techo-confianza=6.0` heredado de Bonsai por error) | Sesión 16 (`ctx-size=8192`, `techo-confianza=8.0`, perfil propio) |
|---|---|---|
| Calificadas | 97/100 (3 con error de infraestructura) | **100/100 (0 con error)** |
| Correctas | 74/97 = **76.3%** | 68/100 = **68.0%** |
| Rechazos indebidos (debía responder, rechazó) | 22/82 (16 `AMBIGUO` + 6 `INSUFICIENTE`) | 32/82 (22 `AMBIGUO` + 10 `INSUFICIENTE`) |
| Respuestas indebidas (debía rechazar, respondió) | 1/18 | 0/18 |

El número global empeoró, pero comparar estas dos corridas de punta a punta compara dos cambios a la
vez, no uno: además de `ctx-size`, esta sesión también corrigió `KB_UMBRAL_RELEVANCIA_TECHO_CONFIANZA`
de 6.0 (el valor de Bonsai, copiado por error en la sesión 14) a 8.0 (el default real de ADR-0008,
fijado en `compose.ministral.yml` desde la sesión 15). Un techo más alto exige un `mejorRerank` más
alto para auto-aceptar sin pasar por `VerificadorGrounding`, así que más preguntas caen en zona
`AMBIGUO` donde el veredicto depende del juicio del modelo — y ese juicio, con el techo que de verdad
le corresponde a Ministral (no el prestado de Bonsai), resultó más estricto de lo que el piloto de la
sesión 14 dejaba ver: los rechazos `AMBIGUO` subieron de 16 a 22, los `INSUFICIENTE` (que ni siquiera
llegan al verificador, es la puerta del reranker) de 6 a 10.

**No hay evidencia de que el desborde de contexto explique este empeoramiento** — al contrario, esta
sesión demuestra que esa causa específica quedó en cero (hallazgo 57). Lo que queda expuesto, con una
muestra limpia de 100/100 sin ruido de errores de infraestructura, es exactamente lo que la sesión 15
ya había marcado como la incógnita más grande sin resolver: *"para las 7 restantes, no hay evidencia
todavía de que sea ni lo uno ni lo otro"* (juicio del modelo vs. problema de recuperación). Esta
sesión no investiga esa pregunta — solo la deja con más peso y mejores datos para la próxima vez que
se retome.

### Hallazgo 59: bug de portabilidad en `ejecutar.js` — un mensaje de Playwright no reconocido convertía un crash de Chromium en una cascada silenciosa de 50 rechazos falsos

Durante la corrida, un crash de `chrome-headless-shell.exe` (mismo síntoma que la sesión 14, presión
de memoria del sistema, `exitCode=3221225794`/`0xC0000005`) hizo que `chromium.launch()` fallara con
el mensaje `Target page, context or browser has been closed` — un mensaje real y común de Playwright
que **no coincidía con ninguna de las tres alternativas** del regex de recuperación en
`ejecutar.js` (`/crashed|Target closed|Protocol error/i`, agregado en la sesión 14 para el caso
`browser.newPage()` que ese mismo hallazgo documentó). Al no matchear, el bloque de
cierre-y-relanzamiento-con-backoff nunca se ejecutaba: la pregunta quedaba marcada como error sin
espera, y como la variable `browser` quedaba apuntando a la referencia vieja ya muerta, **cada
pregunta siguiente repetía el mismo fallo instantáneo**, sin reintentar ni esperar. En la práctica: las
preguntas 51 a 100 de un intento de corrida completaron en ~14 minutos en vez de las ~3 horas reales,
las 50 marcadas como error sin haberse respondido de verdad.

Corregido ampliando el regex a `/crashed|closed|disconnected|Protocol error/i` (línea ~125): "closed"
sin más cubre tanto `Target closed` como `Target page, context or browser has been closed` y
`browser has disconnected`, las tres formas observadas hasta ahora en que Playwright reporta un
navegador muerto. Las 50 preguntas afectadas se identificaron por su mensaje de error idéntico, se
descartaron del `resultados-brutos.json` (quedan solo las entradas sin `.error`) y se re-corrieron de
verdad con el fix aplicado.

### Hallazgo 60: las notificaciones "killed" de tareas en background no son confiables en este entorno Windows — el proceso real puede seguir vivo y huérfano

Repetido al menos 9 veces durante esta sesión: una tarea en background (`bash loop-eval.sh` →
`npm run eval` → `node ejecutar.js` → `chrome-headless-shell.exe`) reportada como `status: killed` por
el harness **no había terminado de verdad** — `Get-CimInstance Win32_Process -Filter
"Name='node.exe' OR Name='bash.exe'" | Select ProcessId,ParentProcessId,CommandLine` seguía mostrando
la cadena completa viva y corriendo. En un caso concreto esto hizo que dos corridas de
`npm run eval` compitieran en paralelo, sin saberlo, por la misma GPU y el mismo
`resultados-brutos.json` (escribiendo entradas duplicadas para el mismo id) — probablemente
contribuyendo a más crashes de Chromium por la contención de recursos, no solo por la presión de
memoria de fondo ya documentada en la sesión 14.

Protocolo adoptado el resto de la sesión, antes de cualquier relanzamiento: verificar el árbol de
procesos real con PowerShell (`Get-CimInstance Win32_Process`, filtrando por `CommandLine` que
contenga `loop-eval`, `run eval` o `ejecutar.js`), matar cada PID encontrado explícitamente con
`taskkill /PID <pid> /F` (el flag `/T`, pensado para matar también los hijos, no alcanzó a los
procesos de `bash.exe` en al menos un caso probado) y `taskkill /IM chrome-headless-shell.exe /F` para
cualquier huérfano, y solo entonces revisar `resultados-brutos.json` por duplicados antes de relanzar.
No se investigó la causa raíz de por qué el kill del harness no se propaga siempre al árbol completo
en Windows — queda como una limitación operativa a tener en cuenta en cualquier corrida larga futura
que dependa de tareas en background en este entorno, no solo para este piloto.

### Herramienta nueva: `eval-100-preguntas/loop-eval.sh`

Envuelve `npm run eval` en un loop que relanza automáticamente mientras queden preguntas pendientes en
`resultados-brutos.json`, matando primero cualquier `chrome-headless-shell.exe` huérfano del ciclo
anterior (un kill externo no le da tiempo a Playwright a cerrar sus hijos — medido en vivo, 8 procesos
huérfanos bajaron la RAM libre del sistema en 1.4 GB en un solo ciclo). No resuelve el hallazgo 60 (la
tarea en background puede seguir muriendo sin avisar de verdad), pero reduce cuánto de la recuperación
depende de que alguien esté mirando en el momento exacto en que Chromium crashea.

### Hallazgo 61: de los 22 rechazos `AMBIGUO`, el 68% son problema de recuperación — no de juicio del verificador

A pedido directo del usuario, se reconstruyó el contexto real (top-6 candidatos de
`query_log.candidates` + texto de `chunks`, mismo criterio que `Orquestador.construirContexto()`) para
las 22 preguntas `AMBIGUO` del hallazgo 58 y se clasificó cada una leyendo si el fragmento correcto de
`jls25.pdf` estaba entre los seis que vio `VerificadorGrounding`:

| Causa | Cantidad | % |
|---|---|---|
| RECUPERACIÓN — el reranker no trajo el fragmento correcto | 15 | 68% |
| JUICIO — el fragmento correcto estaba, el modelo igual rechazó mal | 4 | 18% |
| CORPUS_DÉBIL — el tema está tocado, pero de forma incompleta o indirecta | 3 | 14% |

Invierte la sospecha que la sesión 15 había dejado abierta sin inclinarse por ninguna de las dos: la
mayoría de los rechazos indebidos no son culpa de Ministral como verificador — son culpa de qué
fragmentos le llegan para juzgar. Patrones concretos observados en los 15 casos de RECUPERACIÓN:

- **Colisión de palabra clave**: "¿Qué es un método `default` en una interfaz?" (id 24) trajo el
  fragmento sobre valores *default* de inicialización de campos — mismo término, concepto distinto.
- **Fragmentos "vecinos" del tema correcto, pero no el correcto**: la pregunta 55 (longitud de un
  array) trajo el fragmento de `ArrayStoreException`/covarianza — que es exactamente el fragmento
  correcto para la pregunta 52 (arrays covariantes), de la misma tanda.
- **Java moderno perdiendo contra JLS profundo**: preguntas sobre records, patrones y módulos (Java
  16-25) recuperan reglas de inferencia de tipos o gramática BNF en vez de la prosa introductoria que
  sí explica el concepto — el corpus tiene la respuesta, pero el reranker prefiere el fragmento
  técnicamente más denso.

De los 4 casos de JUICIO real (preguntas sobre overload/override, qué es un record, los accesores
automáticos de un record, y los patrones no nombrados): en los cuatro el fragmento definía el concepto
de forma explícita y directa (ej. *"an implicitly declared accessor method for every component"*,
respuesta literal a "¿cómo se llaman los métodos de acceso de un record?") y Ministral rechazó de
todos modos — estos sí son candidatos limpios para la tarea pendiente de comparar con Bonsai como
verificador, pero son una minoría, no la mayoría.

**Reordena la prioridad que había quedado abierta al cierre de esta sesión**: ampliar la recuperación
(subir `KB_RECUPERACION_TOP_RERANK`/`KB_MAX_FRAGMENTOS_CONTEXTO`, hoy 6/6, o activar
`KB_EXPANDIR_VECINOS`, hoy `false`) tiene mucho más peso esperado que seguir comparando modelos como
verificador — ese segundo camino solo puede mover, como mucho, el 18% de los casos.

### Hallazgo 62: la misma pregunta, el mismo corpus, el rerank cambió ~28x entre la sesión 15 y la 16 — posible no-determinismo en la recuperación, no solo en el juicio

Efecto colateral notado durante el diagnóstico del hallazgo 61, fuera de su alcance: la pregunta
`¿Cómo se declara un método genérico independiente de si la clase que lo contiene es genérica?` es
textualmente idéntica a la que el hallazgo 52 de la sesión 15 había medido con `mejorRerank=5.05` (la
candidata más prometedora en ese momento para bajar `techo-confianza`). En esta corrida, la misma
pregunta contra el mismo corpus dio `mejorRerank=0.182` — casi 28 veces menos. Ninguna de las dos
sesiones cambió el corpus ni el modelo de embeddings entre medio, así que no puede explicarse por una
diferencia de contenido: sugiere que el paso de recuperación por similitud vectorial (candidatos ANN
de `pgvector`, antes del rerank) no es perfectamente determinista para la misma consulta, algo que
ninguna sesión anterior había medido ni puesto en duda. **No investigado más allá de esta observación
puntual** — queda como pregunta abierta para cuando se retome el trabajo de recuperación, porque si la
recuperación en sí es inestable, subir `top-rerank`/`max-fragmentos` podría enmascarar el síntoma sin
explicar la causa.

### Hallazgo 63: el hallazgo 62 no es no-determinismo del motor de búsqueda — y de paso aparece `tope-por-documento=3`, un candidato más fuerte para explicar la `RECUPERACIÓN` del hallazgo 61

Investigando el hallazgo 62 se probaron tres hipótesis contra el código y el pipeline real, en orden:

1. **¿El Planificador reformula la pregunta antes de buscar?** No — descartado leyendo el código:
   `Orquestador.prepararHastaContexto()` pasa `pregunta.texto()` sin modificar a
   `Executor.ejecutar(plan.herramientas(), pregunta.texto(), proyecto)`, y de ahí a
   `Herramienta.ejecutar(consulta, ...)` de cada herramienta elegida. `PlanificadorOpenAi` (el único
   paso con LLM antes de la búsqueda) solo decide QUÉ herramientas correr — nunca ve ni reescribe el
   texto de búsqueda. No existe ningún paso de reescritura de consulta en el pipeline actual.

2. **¿`Buscador` es no-determinista para un estado fijo del sistema?** No — se llamó dos veces
   seguidas a `POST /api/search` (`RecuperacionController`, el mismo camino que usan
   `search_unified`/`search_docs`, sin pasar por ningún LLM) con el texto literal de la pregunta 69:
   resultado idéntico bit a bit en las dos, mismos 6 chunks, mismo `rerank` hasta el último decimal
   (`0.18214967423919637`).

3. **¿Cambió el corpus entre la sesión 14 (cuando corrió la pregunta original, `queryLogId=253`,
   `2026-08-06 14:19:23 UTC`) y ahora?** No — `chunks` no tiene ninguna fila con `created_at`
   posterior al `2026-08-04 20:16`, antes de las tres sesiones del piloto.

Cruzando `candidates` de `queryLogId=253` contra la búsqueda en vivo aparece el dato clave: los tres
chunks que sí coinciden entre ambas corridas (`1047`, `416`, `437`) tienen el mismo `rerank` al
decimal exacto — pero la corrida original trae además otros tres chunks que la búsqueda en vivo, ahora
mismo, ni siquiera trae como candidatos: `426` (`rerank=5.05`, el más alto de los seis), `367`
(`1.19`) y `557` (`0.50`). No es que el mismo chunk puntúe distinto — es que el conjunto de
candidatos que llega a rerankear es distinto.

Revisando `Recuperador.java`/`RecuperacionPropiedades.java` para entender qué podría filtrar esos tres
chunks antes de la etapa de rerank, aparece un límite que ninguna sesión anterior había puesto en
duda para este corpus: `kb.recuperacion.tope-por-documento`
(`KB_RECUPERACION_TOPE_POR_DOCUMENTO`, **default 3, sin overridear en `compose.bonsai.yml` ni
`compose.ministral.yml`**) — ningún documento puede aportar más de 3 candidatos a la fusión RRF antes
del cross-encoder, pensado como tope de diversidad para no dejar que un documento grande acapare todo
(`RrfFusion.fusionar(...)`, comentario: *"Diversidad: ningún documento puede acaparar los
candidatos"*). Con un corpus donde `jls25.pdf` **es** casi todo el corpus (session 9), ese tope de
diversidad no protege contra que un documento grande ahogue a los demás — le pone techo a la propia
`jls25.pdf` compitiendo contra sí misma: si una pregunta tiene 4 o más chunks genuinamente relevantes
dentro de `jls25.pdf`, el cuarto pierde su lugar en la fusión así sea mejor candidato que otro
sobreviviente, sin llegar siquiera a que el cross-encoder lo vea.

El commit `3c11bd3` (14:01 UTC) que fijó los valores actuales de `compose.bonsai.yml` (heredados sin
commitear desde la sesión 9) se hizo 18 minutos antes de que corriera `queryLogId=253` (14:19) — no
hay forma de reconstruir con certeza si el contenedor que sirvió esa pregunta puntual ya corría con
`tope-por-documento=3` o con un valor distinto de la sesión 9 sin registrar en git. **Esta hipótesis
de deriva de configuración no se pudo confirmar ni descartar con la evidencia disponible** — a
diferencia de las otras dos, que sí quedan descartadas con evidencia directa.

**Corrección explícita sobre el hallazgo 62**: la sospecha de no-determinismo en la recuperación
vectorial queda descartada — la búsqueda es determinista para un estado fijo del sistema, medido en
vivo, y tampoco hay reescritura de consulta por un LLM que pudiera explicar la varianza. Sigue sin
resolverse por qué el estado del sistema fue distinto entre las dos mediciones, pero la investigación
deja un candidato concreto y accionable que las tareas pendientes no habían contemplado:
`tope-por-documento` puede estar recortando de más, específicamente para este corpus. Se prioriza
probarlo en la tarea pendiente de ampliar recuperación, antes que `top-rerank`/`max-fragmentos` (que
solo actúan sobre lo que ya sobrevivió a este tope).

### Hallazgo 64: subir `tope-por-documento` a 20 mejora 28 de las 32 preguntas fallidas, ninguna empeora — medido contra `/api/search`, todavía sin confirmar en el pipeline completo

Prueba directa de la hipótesis del hallazgo 63: se agregó `KB_RECUPERACION_TOPE_POR_DOCUMENTO` al
bloque `environment` de `compose.ministral.yml` (sin cambiar el default de `3`, solo para poder
overridearlo desde el shell) y se recreó `kb-api` con el valor en `20` — el mismo que
`KB_RECUPERACION_MAX_CANDIDATOS`, equivalente a desactivar el tope de diversidad para este corpus.
Contra las 32 preguntas rechazadas de más de esta sesión (22 `AMBIGUO` + 10 `INSUFICIENTE`), llamando
`POST /api/search` directo (sin LLM, en segundos en vez de horas):

| Métrica | Valor |
|---|---|
| Subieron su `mejorRerank` | 28/32 |
| Bajaron | 0/32 |
| Quedaron exactamente igual | 4/32 (ids 30, 43, 48, 49) |
| Cruzaron `techo-confianza=8.0` (pasarían a `SUFICIENTE` sin depender del verificador) | 5/32 (ids 1, 12, 23, 31, 64) |
| Cambió el chunk top-1 (candidato nuevo, no solo mejor puntaje) | 28/32 |

Los saltos no son marginales: id 23 sube ×468 (`0.019→8.92`), id 6 ×596 (`0.011→6.74`), id 31 ×86
(`0.098→8.43` — exactamente el caso que el hallazgo 61 había señalado como "el chunk correcto existe
en el corpus pero no se recuperó para esta pregunta"). Las 4 preguntas sin cambio coinciden con lo que
el hallazgo 61 ya había clasificado aparte: la id 30 es el único caso limpio de `JUICIO` (el chunk
correcto ya se encontraba con `tope-por-documento=3`, subir el tope no le aporta nada, es esperable
que no cambie); las ids 43, 48, 49 siguen sin encontrar el chunk correcto ni con el tope relajado —
para esas tres el problema no es la diversidad por documento, es algo más profundo en el ranking o el
embedding de la consulta, sin investigar todavía.

**Esto es solo evidencia de recuperación, no de precisión real**: un `mejorRerank` más alto aumenta la
probabilidad de que `VerificadorGrounding` vea el fragmento correcto, pero no garantiza que el modelo
lo use bien — falta correr el pipeline completo (no solo `/api/search`) para confirmar que la mejora
se traduce en respuestas correctas, y falta descartar regresión sobre las 68 preguntas que ya
respondían bien (esta prueba solo tocó las 32 que fallaban).

### Próximos pasos (pendiente para la próxima sesión)

En orden, sin saltarse pasos — el patrón de esta sesión (mezclar `ctx-size` y `techo-confianza` en la
misma corrida, hallazgo 58) ya costó una comparación confundida una vez:

1. **Chequeo de regresión**: correr el mismo proxy `/api/search` con `tope-por-documento=20` contra
   una muestra de las 68 preguntas que ya respondían bien, para descartar que más candidatos de
   `jls25.pdf` compitiendo diluyan el top-1 de preguntas que ya iban bien. Barato, minutos.
2. **Confirmar en el pipeline real**: si no hay regresión, correr la UI completa (no el proxy) para
   las preguntas con el salto más grande (23, 6, 31, 64, 1, 12) y confirmar que de verdad pasan a
   "responde correcto", no solo que sube el `mejorRerank`.
3. **Elegir un valor y comprometerlo**: si el punto 2 confirma la mejora, decidir el valor de
   `KB_RECUPERACION_TOPE_POR_DOCUMENTO` como default de `compose.ministral.yml` (y evaluar si
   `compose.bonsai.yml` debería recibir el mismo ajuste — el default de `3` nunca fue overrideado ahí
   tampoco). `20` fue el extremo elegido para medir el máximo posible, no necesariamente el valor
   final — probar si `10` alcanza casi lo mismo con menos riesgo antes de comprometerse al extremo.
   Después, re-correr el piloto completo de 100 preguntas para tener el número real (no el proxy).
4. **Tarea de comparar Bonsai como verificador, pausada, no cancelada**: con solo 1 de 32 casos como
   candidato limpio de `JUICIO` (id 30) tras el hallazgo 64, pierde casi toda su justificación frente
   al plan original. Solo retomarla si, después del punto 3, sigue quedando un residuo real de casos
   de juicio que la recuperación no explica.

### Conclusión de la sesión 16

El objetivo puntual de la sesión — verificar si el ajuste de `ctx-size` de la sesión 15 eliminaba el
desborde de contexto en la práctica — se cumplió con evidencia limpia (hallazgo 57, cero ocurrencias
en 100/100 preguntas sin errores de infraestructura, algo que ninguna corrida anterior había logrado).
Que la precisión global haya bajado igual no contradice ese resultado: es un efecto secundario de
corregir, en la misma sesión, un segundo valor (`techo-confianza`) que estaba mal calibrado desde la
sesión 14 sin que nadie lo supiera todavía.

La `vía de mayor impacto pendiente` que cerraba la sesión 15 — por qué el juicio de
`VerificadorGrounding` rechaza más de lo esperado en la zona `AMBIGUO` — ya no queda abierta de la
misma forma: el hallazgo 61 la resolvió parcialmente con una muestra limpia de 100 preguntas. La
respuesta no es "el modelo juzga mal" sino, en dos de cada tres casos, "el modelo nunca vio el
fragmento correcto". La siguiente palanca de mayor impacto es ampliar la recuperación, no seguir
afinando o comparando el verificador — el hallazgo 63 corrige la sospecha de no-determinismo del
hallazgo 62 (la búsqueda sí es determinista) y en el camino encuentra un candidato más concreto que
`top-rerank`/`max-fragmentos-contexto`: `KB_RECUPERACION_TOPE_POR_DOCUMENTO` (default 3, nunca
overrideado), que en un corpus dominado por un solo documento puede estar descartando candidatos
genuinamente relevantes antes de que lleguen al cross-encoder.

Estado del entorno al cierre: `kb-llama-server-ministral` reemplazó a `kb-llama-server` (Bonsai) como
backend del `kb-api` de producción durante toda la sesión (perfil `compose.ministral.yml`, GPU
compartida, no hay margen para tener los dos perfiles arriba a la vez con `ctx-size=8192` en ambos —
ver hallazgo 56). No se volvió a Bonsai al cierre; queda como decisión pendiente del usuario cuál
perfil dejar como default de facto. Los resultados de esta sesión quedan en
`eval-100-preguntas/*-ministral-3-3b-sesion16-verificacion.*`, sin pisar los de la sesión 14
(`*-100preguntas.*`).

`kb-api` corrió el experimento del hallazgo 64 con `KB_RECUPERACION_TOPE_POR_DOCUMENTO=20` pasado por
el shell — **se revirtió a `3` (el default) antes de cerrar la sesión**, recreando el contenedor sin
esa variable: el valor de `20` todavía no pasó el chequeo de regresión del paso 1 de "Próximos pasos",
dejarlo activo sin esa validación habría expuesto uso real de la base de conocimiento a un cambio sin
confirmar. `compose.ministral.yml` sí queda con la línea `KB_RECUPERACION_TOPE_POR_DOCUMENTO:
${KB_RECUPERACION_TOPE_POR_DOCUMENTO:-3}` agregada (default `3`, sin efecto salvo que se pase la
variable) — el andamiaje para retomar el experimento sin tener que editar el archivo de nuevo.

## Sesión 17: se compromete `tope-por-documento=20` como default de `compose.ministral.yml` — sin regresión medida y confirmado en el pipeline real

Retoma los 4 pasos que la sesión 16 dejó documentados como plan explícito. Los primeros tres se
completan en esta sesión; el cuarto (retomar la comparación de Bonsai como verificador) sigue
pausado, con la misma justificación de entonces.

### Hallazgo 65: paso 1 — chequeo de regresión, ninguna de las 68 preguntas buenas baja su `mejorRerank` con `tope-por-documento=20`

Se agregó soporte de `EVAL_IDS` (lista de ids separados por coma) y `EVAL_SUFIJO` (sufijo de archivo
de salida) a `ejecutar.js` y `reporte.js`, para poder correr una muestra puntual de `preguntas.json`
sin pisar los resultados de una corrida completa ni repetirla entera. Con `kb-api` primero en el
default (`tope=3`, confirmado con `docker inspect`) y después recreado con `tope=20`, se llamó
`POST /api/search` (sin LLM) para las 68 preguntas que la sesión 16 ya calificaba como buenas
(`comportamiento` correcto, sin importar si `esperado` era `responde` o `rechaza`):

| Métrica | Valor |
|---|---|
| Corrida en vivo con `tope=3` vs el `mejorRerank` ya registrado en la sesión 16 | idéntico en las 68 (0 diferencias) |
| Bajan su `mejorRerank` con `tope=20` | 0/68 |
| Suben | 21/68 |
| Quedan exactamente igual | 47/68 |
| Cambia el chunk top-1 (aunque el puntaje no baje) | 21/68 |

La corrida en vivo con `tope=3` reproduce bit a bit los valores de `mejorRerank` que ya estaban
guardados en `resultados-completos.ministral-3-3b-sesion16-verificacion.json` — confirmación adicional
del determinismo que ya había establecido el hallazgo 63, y evidencia de que el proxy mide exactamente
lo mismo que el pipeline completo. **No hay regresión**: subir el tope no le quita candidatos a
ninguna pregunta que ya iba bien. El dato que sí merece quedar anotado sin ser una regresión: en 21 de
las 68 preguntas buenas cambia cuál chunk queda en el puesto top-1, aunque el `mejorRerank` no baje —
más candidatos de `jls25.pdf` compitiendo mueve el ranking interno incluso cuando no lo empeora. No se
investigó si el chunk nuevo es igual de correcto que el viejo para esas 21; queda como posible fuente
de sorpresas si se audita a mano una respuesta que antes citaba un fragmento distinto.

### Hallazgo 66: paso 2 — confirmado en el pipeline real (no solo en el proxy): las 6 preguntas del salto más grande pasan de rechazar a responder correcto

Con `kb-api` en `tope=20`, se corrieron las 6 preguntas con el salto más grande del hallazgo 64
(ids 1, 6, 12, 23, 31, 64) contra la UI real vía Playwright (`EVAL_IDS="1,6,12,23,31,64"
EVAL_SUFIJO="tope20-confirmacion-6preguntas" npm run eval`, después `npm run reporte` con el mismo
sufijo para cruzar con `query_log`):

| id | Antes (`tope=3`) | Ahora (`tope=20`) |
|---|---|---|
| 1 | `AMBIGUO`, rechaza, `mejorRerank=2.505` | `SUFICIENTE`, responde, correcta, `mejorRerank=8.093` |
| 6 | `INSUFICIENTE`, rechaza, `mejorRerank=0.011` | `AMBIGUO`, responde, correcta, `mejorRerank=6.737` |
| 12 | `AMBIGUO`, rechaza, `mejorRerank=7.691` | `SUFICIENTE`, responde, correcta, `mejorRerank=9.826` |
| 23 | `INSUFICIENTE`, rechaza, `mejorRerank=0.019` | `SUFICIENTE`, responde, correcta, `mejorRerank=8.921` |
| 31 | `AMBIGUO`, rechaza, `mejorRerank=0.098` | `SUFICIENTE`, responde, correcta, `mejorRerank=8.431` |
| 64 | `AMBIGUO`, rechaza, `mejorRerank=0.266` | `SUFICIENTE`, responde, correcta, `mejorRerank=9.083` |

6/6 correctas (100%), incluida la id 6 que sube su `mejorRerank` pero se queda en zona `AMBIGUO`
(6.737 < techo 8.0) y aun así responde correcto — el verificador de grounding sí acierta su juicio
cuando el fragmento correcto está entre los candidatos, coherente con el diagnóstico del hallazgo 61.
Esto cierra la duda que dejaba abierta el hallazgo 64: un `mejorRerank` más alto vía `/api/search` sí
se traduce en respuestas correctas en el pipeline real, no es solo una mejora de recuperación que se
pierde después en la síntesis o el juicio del verificador.

### Hallazgo 67: paso 3 — `tope=10` no es una alternativa de "menos riesgo": pierde por completo el caso más fuerte de la muestra confirmada

Se comparó `mejorRerank` para las 32 preguntas rechazadas de más entre los tres valores de tope,
midiendo los tres con el mismo script (`chequeo-32-fallidas.mjs`, misma llamada a `/api/search` que el
hallazgo 65) para que la comparación sea directa:

| | `tope=3` (baseline) | `tope=10` | `tope=20` |
|---|---|---|---|
| Cruzan `techo-confianza=8.0` | 0/32 | 4/32 | 5/32 |
| Suben `mejorRerank` vs baseline | — | 24/32 | 28/32 |
| Bajan vs baseline | — | 0/32 | 0/32 |

El caso que decide la comparación es la id 1 — la primera de la muestra de 6 que el hallazgo 66 ya
confirmó en el pipeline real: con `tope=10`, su `mejorRerank` es `2.505`, **idéntico** al baseline de
`tope=3` (cero mejora); recién cruza a `8.093` con `tope=20`. Los demás candidatos a `techo-confianza`
(ids 12, 64, 23, 31) sí llegan igual con `tope=10`, pero la id 1 necesita específicamente los
candidatos que aparecen entre las posiciones 11 y 20 — para esa pregunta puntual, `jls25.pdf` tiene más
de 10 chunks compitiendo antes de que el correcto aparezca. `tope=10` deja ese caso exactamente donde
estaba.

`tope=10` no ofrece ninguna ventaja de riesgo medible que justifique perder ese caso: el hallazgo 65 ya
mostró que `tope=20` no perjudica ninguna de las 68 preguntas que iban bien (0 regresiones), así que no
hay evidencia de que `tope=10` evite un daño real que `tope=20` sí cause. Se descarta `10` y se
mantiene `20` como el valor a comprometer.

### Decisión: `KB_RECUPERACION_TOPE_POR_DOCUMENTO=20` pasa a ser el default de `compose.ministral.yml`

Con los tres pasos anteriores confirmando la mejora sin regresión, se cambió el default de la línea
`KB_RECUPERACION_TOPE_POR_DOCUMENTO: ${KB_RECUPERACION_TOPE_POR_DOCUMENTO:-3}` a `:-20}` en
`compose.ministral.yml`, y se recreó `kb-api` sin ninguna variable de shell — confirmado con
`docker inspect` que corre con `20` tomado del nuevo default del archivo, no de un override temporal.
`compose.bonsai.yml` **no** se tocó: sigue en el default de `application.yml` (`3`) sin overridear,
la misma situación que tenía antes de esta investigación — queda pendiente evaluar si Bonsai, con el
mismo corpus dominado por `jls25.pdf`, se beneficiaría igual, pero no hay evidencia propia todavía
(todo lo medido en las sesiones 16 y 17 corrió contra el perfil Ministral).

### Próximos pasos

1. **Piloto completo de 100 preguntas** con `tope=20` ya como default, para tener el número real de
   precisión (no el proxy ni una muestra de 6) y confirmar que la mejora se sostiene a la escala
   completa. Es la validación que falta antes de considerar cerrado el ajuste — pero corre contra el
   pipeline real con LLM y tarda del orden de horas (la sesión 16 completa tardó eso), así que no se
   lanzó en esta sesión sin que el usuario lo pida explícitamente.
2. **Bonsai como verificador, pausada, no cancelada** (arrastrada de la sesión 16 sin cambios: con
   solo 1 de 32 casos como candidato limpio de `JUICIO`, pierde casi toda su justificación frente al
   plan original — retomar solo si el piloto completo del punto 1 deja un residuo real de casos que la
   recuperación no explica).
3. **`compose.bonsai.yml` sin decisión**: evaluar si conviene el mismo ajuste de `tope-por-documento`
   cuando/si se retome ese perfil — no hay evidencia propia todavía, solo la inferencia de que el
   mismo corpus debería comportarse parecido.

### Hallazgo 68: recurrencia del hallazgo 60 — 6 avisos de "killed" seguidos sin que el proceso muriera de verdad, esta vez con la causa raíz identificada: 5 sesiones de `ejecutar.js` concurrentes peleando por la GPU

El piloto completo de 100 preguntas (paso siguiente tras el hallazgo 67) reprodujo el problema que el
hallazgo 60 ya había documentado en la sesión 16 — un aviso de `status: killed` del harness sobre un
comando en background **no significa que el proceso muera de verdad** — pero esta vez con evidencia más
directa de la causa raíz y del costo real de no revisarlo a tiempo. Seis relanzamientos seguidos de
`EVAL_SUFIJO=ministral-3-3b-sesion17-tope20 npm run eval` recibieron `status: killed` sin haber llegado a
100/100, sin ningún error propio en el log del script. Los primeros tres relanzamientos se hicieron a
ciegas (el mismo error que el hallazgo 60 ya advertía evitar) — recién al notar que la pregunta 36 nunca
avanzaba pese a reintentos sucesivos se investigó con `wmic process where "name='node.exe'" get
ProcessId,ParentProcessId,CommandLine`: **5 procesos `node ejecutar.js` completamente vivos y
concurrentes** (lanzados en momentos distintos, ninguno terminado de verdad), más **~21 procesos
`msedge.exe`** (el canal de Chromium que usa Playwright en este entorno ahora — no
`chrome-headless-shell.exe` como en la sesión 16, dato a tener en cuenta si se reintenta el protocolo del
hallazgo 60 tal cual), todos compitiendo por el único slot de inferencia de `kb-llama-server-ministral`.

Evidencia concreta de la contención: una llamada a `POST /api/search` con una consulta trivial (`"test"`,
sin LLM, debería resolver en menos de 1 segundo con la GPU libre) tardó **20.1 segundos**; los logs del
propio `llama-server` mostraban una sola llamada de prompt-eval tardando **261 segundos para 4932
tokens** — consistente con varios clientes simultáneos turnándose el único slot disponible. La pregunta
36 no era intrínsecamente lenta: cada "reintento" era en realidad una sexta sesión más sumándose a la
pila, nunca alcanzaba a terminar antes de que llegara el siguiente aviso de "killed" (que tampoco mataba
nada).

Con autorización explícita del usuario (el `taskkill` fue bloqueado por el clasificador de auto-mode por
default), se terminaron los 10 procesos `node.exe` huérfanos y los 21 `msedge.exe` (`taskkill /F /IM
msedge.exe /T`, que sí propagó a todo el árbol esta vez). Con la GPU libre, un solo proceso limpio avanzó
sin cortes reales el resto del piloto (con 2 fallos más, genuinos y no de contención, cubiertos en el
hallazgo 69).

**Actualización al protocolo del hallazgo 60**: verificar el árbol de procesos real (`wmic process where
"name='node.exe'" get ProcessId,CommandLine`, o el `Get-CimInstance` de PowerShell que ya proponía el
hallazgo 60) es un paso que **hay que repetir cada vez** que llega un aviso de "killed", no solo la
primera vez que se sospecha del problema — la existencia del hallazgo 60 no evitó que esta sesión
relanzara a ciegas tres veces antes de investigar. `eval-100-preguntas/loop-eval.sh` (herramienta que la
sesión 16 dejó lista para automatizar justo este ciclo de limpieza-y-relanzamiento) no se usó esta sesión
porque no soporta `EVAL_SUFIJO` y apunta a `chrome-headless-shell.exe` en vez de `msedge.exe` — quedaría
como mejora concreta antes de la próxima corrida larga: parametrizar el sufijo y el nombre del proceso de
browser, para no tener que repetir el diagnóstico manual de este hallazgo.

### Hallazgo 69: confirmado en el piloto completo de 100 preguntas — `tope=20` sube la precisión de 68% a 85.3% (medido, no proyectado), sin perder ninguna de las que ya rechazaba bien

Con `tope-por-documento=20` como default de `compose.ministral.yml` (decisión del hallazgo 67, sin ningún
otro cambio de configuración respecto a la sesión 16), se corrió el piloto completo de 100 preguntas
contra el pipeline real (`ejecutar.js` + `reporte.js`, resultados en
`eval-100-preguntas/*-ministral-3-3b-sesion17-tope20.*`):

| Métrica | Sesión 16 (`tope=3`, baseline) | Sesión 17 (`tope=20`) |
|---|---|---|
| Precisión global | 68/100 (68.0%) | 81/95 (85.3%) |
| Entre las que debían responder | 50/82 (61.0%) | 65/79 (82.3%) |
| Entre las que debían rechazar | 18/18 (100.0%) | 16/16 (100.0%) |
| Decisión `SUFICIENTE` | 22 | 28 |
| Decisión `AMBIGUO` | 58 | 52 |
| Decisión `INSUFICIENTE` | 20 | 15 |
| Errores de infraestructura (no calificados) | 0 | 5 |

+21.3 puntos porcentuales exactamente donde se esperaba el impacto (las preguntas que debían responder),
sin perder nada en las que debían rechazar (100% en ambas sesiones) — confirma en el pipeline completo lo
que los hallazgos 65-66 ya habían mostrado con el proxy `/api/search` y con la muestra de 6 preguntas.

**Los 5 errores de infraestructura** (ids 34, 35, 36, 90, 91: dos por "se perdió la conexión con
Ollama", tres por timeout de 15 minutos) coinciden en parte con la ventana de contención del hallazgo 68
— específicamente 34-36 cayeron durante el tramo con 5 sesiones concurrentes peleando por la GPU; 90 y 91
fueron un crash real y aislado del navegador más adelante (`exitCode=3221225794`, el mismo defecto de
memoria que documentaron las sesiones 14 y 16). Se excluyen de la precisión con el mismo criterio que
usa `reporte.js` en todas las sesiones anteriores (un error de infraestructura no es ni acierto ni
rechazo, no se califica) — pero para no ocultar el peor caso: si se contaran las 5 como incorrectas, la
precisión global sería 81/100 (81.0%), igual muy por encima del 68.0% de la sesión 16.

### Conclusión de la sesión 17

Los tres primeros pasos del plan que dejó la sesión 16 quedan completados con evidencia real, no
proyectada: regresión descartada (hallazgo 65), mejora confirmada en una muestra chica del pipeline real
(hallazgo 66), valor final elegido con criterio frente a la alternativa de "menos riesgo" (hallazgo 67),
y ahora confirmado a escala completa (hallazgo 69). `tope-por-documento=20` queda como default de
`compose.ministral.yml`.

Fuera del plan original, esta sesión también:

- Diagnosticó y corrigió en el código el problema de vocabulario/idioma detrás de una de las preguntas
  que ni `tope=20` resolvía (id 4, "autoboxing" vs. el término formal "boxing conversion" del corpus en
  inglés, confirmado reformulando la pregunta a mano y viendo el chunk correcto saltar de ausente a
  `rerank=6.58`) — `Reformulador`/`ReformuladorOpenAi` (paquete `llm`), cableado en `Orquestador` antes
  de ejecutar las herramientas de búsqueda (sin tocar el plan de herramientas ni la síntesis final), con
  la consulta reformulada expuesta en la UI (evento SSE `reformulacion`, `index.html`/`app.js`). Código
  escrito con tests pasando (`OrquestadorTest`, `ChatControllerTest`, `ArquitecturaTest` sin violaciones
  de capas) pero **deliberadamente sin desplegar** — se decidió no reconstruir la imagen ni recrear
  `kb-api` para no arriesgar el piloto de 100 preguntas que corría en paralelo. Queda como el primer
  paso pendiente de la próxima sesión.
- Encontró, con más evidencia que la sesión 16, una recurrencia del problema operativo del hallazgo 60
  (hallazgo 68) — vale la pena revisarlo antes de correr otro piloto largo en este entorno.

### Hallazgo 70: desplegado el `Reformulador` — el campo `reformulada` que devuelve el propio modelo no es confiable, hay que derivarlo comparando el texto

Con el piloto terminado y la GPU libre, se desplegó el `Reformulador` (reconstruyendo la imagen y
recreando `kb-api` con `docker compose ... up -d --build --no-deps api`) y se probó en vivo la pregunta
id 4 ("¿Qué es el autoboxing...?", el caso que motivó el componente). **Primer intento: siguió
rechazando** — mismos seis candidatos de siempre en `query_log.candidates` (sin el chunk 205/206), pese a
tener el `Reformulador` ya desplegado. Repetido dos veces más, mismo resultado: no era un problema de
determinismo puntual.

Se agregó un log temporal (`log.info` de la respuesta cruda del `ChatClient`) y se encontró la causa:
Ministral-3-3B **sí generaba** un `textoBusqueda` distinto del original
(`"autoboxing conversion in Java automatic occurrence conditions"` para la pregunta de autoboxing), pero
devolvía `reformulada=false` en la misma respuesta estructurada — el código de `ReformuladorOpenAi`
confiaba en ese booleano propio del modelo para decidir si usar el texto nuevo, así que descartaba una
reformulación que sí había ocurrido. Un modelo de 3B con salida forzada por esquema JSON no garantiza
consistencia entre dos campos de la misma respuesta -- confiar en un campo booleano que el modelo
autoevalúa sobre su propia salida es más frágil que derivar el mismo dato de una comparación de texto
hecha en código.

**Corrección**: `reformular()` ya no lee el booleano del modelo — compara `textoBusqueda` contra la
pregunta original (`strip()` + `equalsIgnoreCase`) y deriva `reformulada` de esa comparación. Es la única
señal que no depende de que el modelo "sepa" que reformuló. Recompilado, redesplegado, y confirmado en
`query_log`: el chunk 205 ("Boxing conversion treats expressions...") pasa a ser el candidato top-1
(`rerank=1.76`, de estar completamente ausente) y la respuesta final describe el autoboxing correctamente
citando la fuente.

### Conclusión de la sesión 17 (actualizada)

### Hallazgo 71: el `Reformulador` corrige 9 de las 14 preguntas que seguían fallando incluso con `tope=20`, sin regresión en una muestra de 9 preguntas que ya iban bien

Con el fix del hallazgo 70 desplegado, se identificaron las 14 preguntas de `resultados-completos.ministral-3-3b-sesion17-tope20.json` que seguían incorrectas pese al `tope=20` (calificadas, sin error, `correcta=false`) y se corrieron de nuevo contra el pipeline real, ahora con el `Reformulador` activo (`EVAL_IDS` con los 14 ids, `EVAL_SUFIJO=reformulador-14fallidas`):

| id | Antes (`tope=20`, sin Reformulador) | Ahora (con Reformulador) |
|---|---|---|
| 4 | incorrecta, `mejorRerank=0.033` | **corregida**, `mejorRerank=5.625` |
| 14 | incorrecta, `mejorRerank=0.167` | **corregida**, `mejorRerank=4.495` |
| 18 | incorrecta, `mejorRerank=2.763` | **corregida**, `mejorRerank=6.799` |
| 47 | incorrecta, `mejorRerank=0.008` | **corregida**, `mejorRerank=1.617` |
| 48 | incorrecta, `mejorRerank=0.004` | sigue mal, `mejorRerank=0.390` |
| 49 | incorrecta, `mejorRerank=0.038` | **corregida**, `mejorRerank=0.124` |
| 52 | incorrecta, `mejorRerank=1.063` | sigue mal, `mejorRerank=1.528` |
| 62 | incorrecta, `mejorRerank=0.008` | **corregida**, `mejorRerank=0.529` |
| 63 | incorrecta, `mejorRerank=0.145` | **corregida**, `mejorRerank=1.340` |
| 65 | incorrecta, `mejorRerank=1.336` | **corregida**, `mejorRerank=8.147` |
| 68 | incorrecta, `mejorRerank=0.040` | sigue mal, `mejorRerank=0.154` |
| 69 | incorrecta, `mejorRerank=5.051` | **corregida**, `mejorRerank=8.564` |
| 74 | incorrecta, `mejorRerank=2.972` | sigue mal, `mejorRerank=3.870` |
| 75 | incorrecta, `mejorRerank=1.312` | sigue mal, `mejorRerank=6.875` |

**9/14 corregidas (64.3%)**, y en las 14 el `mejorRerank` sube o queda igual — ninguna baja. Las 5 que
siguen mal (48, 52, 68, 74, 75) no son necesariamente fallas del `Reformulador`: varias suben bastante el
`mejorRerank` (75 pasa de 1.312 a 6.875) sin cruzar el veredicto del `VerificadorGrounding`, lo que apunta
a un problema distinto (zona `AMBIGUO` donde el juicio del verificador falla, no la recuperación) —
consistente con el diagnóstico del hallazgo 61 de la sesión 16.

Chequeo de regresión: se tomó una muestra de 9 preguntas que ya respondían correcto con `tope=20` (ids 1,
11, 22, 31, 43, 56, 70, 81, 92, repartidas por todo el rango de las 81 buenas) y se corrieron con el
`Reformulador` activo — **9/9 siguieron correctas (100%)**. No se pudo confirmar por `query_log` si el
`Reformulador` se activó para alguna de estas 9 (la consulta reformulada no se persiste en la base,
solo se expone por SSE) — queda como mejora pendiente de observabilidad, no bloquea la conclusión: no
hubo regresión medible en el resultado final, se haya activado o no.

**Proyección** (no una corrida completa de 100 preguntas, que tomaría ~4h más): si se combinan los 81
aciertos ya confirmados de la sesión 17 con las 9 correcciones nuevas de esta muestra, la precisión
proyectada subiría de 85.3% a **90/95 (94.7%)** sobre las mismas 95 preguntas calificadas — pendiente de
confirmar con un piloto completo si se necesita el número oficial.

### Conclusión de la sesión 17 (actualizada)

Estado del entorno al cierre: `kb-api` corre el `.jar` con el `Reformulador` ya desplegado, corregido
(hallazgo 70) y confirmado en una muestra amplia sin regresión (hallazgo 71).

### Hallazgo 72: el `Reformulador` pasa a llamarse solo cuando la búsqueda inicial no llega a `SUFICIENTE`

A pedido del usuario, se condicionó la llamada al `Reformulador`: hasta el hallazgo 71 se llamaba en
**cada** pregunta, sin importar si hacía falta — una llamada al LLM de más incluso en las preguntas que
ya buscaban bien con el texto original. `Orquestador.prepararHastaContexto()` ahora busca primero con la
pregunta tal cual, evalúa `UmbralRelevancia` sobre ese resultado, y solo si la decisión **no** es
`SUFICIENTE` (cubre tanto `AMBIGUO` como `INSUFICIENTE` — el caso que motivó el componente, la pregunta de
"autoboxing", era `INSUFICIENTE`) llama al `Reformulador` y, si de verdad reformula, repite la búsqueda
con el texto nuevo antes de seguir con el resto del pipeline.

Confirmado en vivo tras recompilar y redesplegar: la pregunta 1 (ya `SUFICIENTE` con el texto original,
`mejorRerank=8.093`) responde correcto sin pasar por el `Reformulador`; la pregunta 4 (autoboxing, sigue
sin llegar a `SUFICIENTE` con el texto original) dispara el segundo intento y responde correcto igual
(esta vez por la vía `AMBIGUO` + `VerificadorGrounding`, no `SUFICIENTE` directo como en el hallazgo 70 —
el `Reformulador` no es determinista, cada llamada al LLM puede generar una consulta reformulada distinta
y aun así cruzar el umbral por una vía diferente). Tests nuevos en `OrquestadorTest`: uno confirma que las
herramientas reciben primero la pregunta original y después el texto reformulado solo cuando el primer
intento no alcanza; otro confirma con un mock que el `Reformulador` **no** se invoca ni una vez cuando el
primer intento ya es `SUFICIENTE`.

**Trade-off de costo, no medido con precisión todavía**: para las preguntas fáciles (ya `SUFICIENTE` a la
primera) esto ahorra una llamada al LLM completa. Para las preguntas difíciles, el costo sube frente al
diseño incondicional del hallazgo 70 -- ahora hacen *dos* rondas de búsqueda en vez de una (aunque
siguen gastando solo una llamada al `Reformulador`, igual que antes). No se comparó latencia end-to-end
entre ambos diseños con las mismas preguntas bajo las mismas condiciones de carga de GPU -- los números
sueltos medidos (100s para la pregunta 1, 488s para la pregunta 4) no son directamente comparables contra
corridas anteriores por la variabilidad de contención de GPU ya documentada en el hallazgo 68.

### Conclusión de la sesión 17 (actualizada de nuevo)

`KB_RECUPERACION_TOPE_POR_DOCUMENTO=20` sigue siendo el default activo de `compose.ministral.yml`,
confirmado con `docker inspect`. `kb-api` corre el `.jar` con el `Reformulador` condicionado (hallazgo 72)
ya desplegado. Los procesos huérfanos del hallazgo 68 quedaron terminados y la GPU liberada. Pendiente
para la próxima sesión: la tarea que el usuario ya aprobó de descargar y probar `Qwen3.5 4B` (sin hacer
todavía — la GPU está libre para intentarlo); opcionalmente, un piloto completo de 100 preguntas con el
`Reformulador` condicional activo para reemplazar la proyección del hallazgo 71 por un número medido bajo
el diseño final; y agregar la consulta reformulada a `query_log` para poder auditar cuándo se activa sin
depender de logs temporales.

## Sesión 18: `qwen3.5:4b` — el thinking obligatorio rompe las dos etapas de salida JSON forzada; se identifica el fix (`reasoning_effort:none`) pero no se integra

Retoma el pendiente que cerraba la sesión 17. Antes de probarlo, se evaluaron dos candidatos que el
usuario propuso de pasada: `qwen3.6:4b` no existe — el tag más chico de la serie `qwen3.6` en la
librería oficial de Ollama es `27b` (17 GB en `Q4_K_M`), descartado sin probar por tamaño, ni de lejos
compatible con esta GPU de 4 GB; y `empero-ai/Qwen3.8-4B`, una destilación fine-tuned de una
organización comunitaria pequeña (341 descargas/mes) sobre la misma arquitectura `Qwen3.5-4B`, con
chain-of-thought horneado en **cada** respuesta y sin ningún parámetro para desactivarlo (el propio
README recomienda dejarlo pensar y recortar el bloque `<think>` en post-procesamiento) — descartado sin
descargar por reproducir de entrada el modo de falla que esta misma sesión termina confirmando con el
modelo base.

`qwen3.5:4b` sí es oficial (Alibaba, vía la librería de Ollama, arquitectura `qwen35`, Apache 2.0,
contexto nativo 262144, 4.7B parámetros, `Q4_K_M`, 3.4 GB en disco). Mismo entorno y metodología que el
resto de la investigación: perfil `compose.ministral.yml` como base (Ministral sigue de producción,
sin tocar), `qwen3.5:4b` descargado en el mismo `kb-ollama`, y un `kb-api-test` temporal (`docker
compose run -d --no-deps -p 8081:8080 -e KB_LLM_MODELO=qwen3.5:4b -e
KB_UMBRAL_RELEVANCIA_HABILITADO=false`) contra los mismos dos casos canónicos (pregunta de despliegue,
pregunta de control "Java 25") de todas las sesiones anteriores.

### Hallazgo 73: reparto CPU/GPU y confirmación de que `think:false` sí funciona contra `/api/generate`, a diferencia del bug de `qwen3:4b`

`ollama show qwen3.5:4b` confirma la capacidad `thinking` (además de `tools` y `vision`). Cargado,
`ollama ps` mide **53% CPU / 47% GPU** (3.8 GB) — peor ajuste que `qwen2.5:3b` (100% GPU, sesión 4) pero
mejor que `gemma3:4b` (60/40, hallazgo 1). Contra `/api/generate` con `think:false` explícito, una
pregunta trivial responde en **2-4 s en caliente**, sin fuga de razonamiento en el campo `response` — a
diferencia del bug de `qwen3:4b` documentado en el hallazgo 5 (`ollama/ollama#12917`), acá `think:false`
sí suprime el pensamiento de verdad en este endpoint. Sin `think` explícito (el comportamiento por
defecto), la misma pregunta trivial **no terminó ni pasados 4 minutos** — se canceló manualmente
(`TaskStop`) para no dejar el slot de `kb-ollama` ocupado; confirma que el modo pensamiento está
**activado por defecto**.

### Hallazgo 74: como `Planificador` (`maxTokens=80`, salida JSON forzada), el plan cae al respaldo el 100% de las veces — el thinking se come todo el presupuesto de tokens sin dejar nada para el JSON

Las dos preguntas del `kb-api-test` (relevante y de control) cayeron en el mismo log:
`PlanificadorOpenAi: Fallo al planificar, se usa search_unified como respaldo:
tools.jackson.databind.exc.MismatchedInputException: No content to map due to end-of-input` — el campo
`content` de la respuesta llegó vacío, indistinguible para Jackson de no haber recibido nada.

Reproducido de forma aislada contra `POST /v1/chat/completions` (mismo endpoint que usa
`PlanificadorOpenAi` vía `OpenAiChatModel`) con el mismo `max_tokens=80` real y `response_format:
json_schema`: la respuesta trae `"content": ""` y un campo `"reasoning"` aparte con el pensamiento
cortado a mitad de frase por `finish_reason: "length"` — Ollama expone el pensamiento en un campo propio
en su API compatible con OpenAI, pero **ese campo consume el mismo presupuesto de `max_tokens`** que el
`content` final, y el modelo no llega a escribir el JSON antes de agotarlo. Con `max_tokens=800` pasa
lo mismo (0 tokens de `content`, razonamiento divagando sin decidirse). Recién con `max_tokens=3000`
(88 s) convergió a un JSON válido — casi 40 veces el presupuesto real de `PlanificadorOpenAi`, y con un
costo de latencia que por sí solo ya lo descarta para una etapa que se llama en cada pregunta.

### Hallazgo 75: como `VerificadorGrounding` (`maxTokens=40`), el veredicto falla el 100% de las veces — con la puerta de relevancia encendida en producción, rechazaría cada pregunta

Mismo patrón, confirmado con los dos casos canónicos del ADR-0008 (contexto de `despliegue.md`,
pregunta relevante y de control) contra `/v1/chat/completions` con el prompt de sistema real de
`VerificadorGroundingOpenAi` y `max_tokens=40`: **las dos llamadas devuelven `content: ""`**, la misma
excepción de Jackson que el hallazgo 74. A diferencia del `Planificador` (que se cae a
`search_unified`), el catch de `VerificadorGroundingOpenAi.verificar()` trata cualquier fallo que no sea
desborde de contexto como "no se pudo verificar" y devuelve `false` por precaución (ver el comentario
del propio código: "arriesgar una alucinacion es peor que negarse"). Con la puerta de relevancia en su
valor de producción (`KB_UMBRAL_RELEVANCIA_HABILITADO=true`, default), esto significa que
**`qwen3.5:4b` rechazaría el 100% de las preguntas, incluidas las legítimas** — un modo de falla más
severo que cualquiera de los doce candidatos anteriores, ninguno de los cuales rompía la puerta de forma
sistemática e incondicional.

### Hallazgo 76: se encuentra el fix — `reasoning_effort:"none"` sí lo desactiva vía la API compatible con OpenAI; `think:false` y `chat_template_kwargs.enable_thinking:false` no

Antes de descartar el candidato, se probaron tres formas de apagar el pensamiento contra
`/v1/chat/completions` (la API que de verdad usa el pipeline, a diferencia del `/api/generate` nativo
del hallazgo 73):

| Parámetro probado | Resultado |
|---|---|
| `"think": false` (nivel superior del body) | Ignorado — siguió pensando y llenó `max_tokens` igual |
| `"chat_template_kwargs": {"enable_thinking": false}` | Ignorado — mismo resultado |
| `"reasoning_effort": "none"` | **Funciona** — responde directo, sin campo `reasoning`, `finish_reason: "stop"` |

Con `reasoning_effort:"none"` agregado, se repitieron los dos veredictos canónicos del hallazgo 75 con
el `max_tokens=40` real: **los dos correctos** (`true` para la pregunta de despliegue, `false` para la
de control), en 14 tokens de `content` cada uno — sobra margen dentro del presupuesto real, nada del
problema del hallazgo 75. El `Planificador` (prompt simplificado, sin el catálogo completo ni las
salvedades código-vs-documentación del prompt real) también convergió con `max_tokens=80` en 13 s, 33
tokens — aunque sin las guardas completas del prompt real, esta prueba aislada no es evidencia de que
elegiría bien las herramientas, solo de que converge dentro del presupuesto.

Este parámetro no está cableado hoy: `PlanificadorOpenAi`, `VerificadorGroundingOpenAi` y
`SintetizadorOpenAi` arman su `OpenAiChatOptions` con `extraBody(Map.of("repeat_penalty", 1.1))` (ver
comentario de `PlanificadorOpenAi` sobre por qué usa `extraBody` y no un campo propio de
`OpenAiChatOptions`) — agregar `"reasoning_effort", "none"` a ese mismo mapa es, en principio, el mismo
patrón ya usado para `repeat_penalty`. **No se integró en esta sesión**: confirmar que de verdad arregla
el `Planificador` con el prompt completo (catálogo real, salvedades código-vs-documentación) y medir el
costo de latencia real con el pipeline completo queda pendiente, y tocar código de producción para un
candidato que todavía no se decidió adoptar no es el alcance de esta sesión.

### Hallazgo 77: como `Sintetizador` (`maxTokens=512`, texto libre), el resultado es inconsistente — buena citación en la pregunta relevante, respuesta truncada sin ninguna cita en la de control

A diferencia de las dos etapas con salida JSON forzada, `Sintetizador` no lanza una excepción cuando el
pensamiento se come el presupuesto: como es texto libre, Spring AI igual devuelve lo que haya en
`content`, vacío o no. Con `maxTokens=512` el resultado fue opuesto entre las dos preguntas:

- **Pregunta relevante** ("como se despliega el servicio", 283 s de latencia total): respuesta completa
  y bien formada, con **una sola cita `[1]` por afirmación puntual** — la misma calidad de citación que
  tuvo Bonsai en el hallazgo 12, mejor que la sobre-citación de `gemma3:4b` (hallazgo 6) y que el pegado
  de texto crudo de `granite4.1:3b`/`phi4-mini:3.8b`/`qwen2.5:3b` (hallazgos 3, 8, 9).
- **Pregunta de control** ("explícame cómo usar Java 25", 277 s): la respuesta quedó **cortada a mitad
  de frase** — *"Para usar Java 25, el proceso de inicio del JVM implica invocar un método main de una
  clase o interfaz"*, sin punto final y **sin ninguna cita `[n]`** — y, a diferencia de los doce
  candidatos anteriores (que alucinaban pegando las instrucciones de despliegue como si respondieran la
  pregunta), acá el modelo sí intentó engancharse con el contenido real de `jls25.pdf` (arranque de la
  JVM) antes de quedarse sin presupuesto de tokens a mitad de camino — una forma de falla nueva, no una
  repetición de la alucinación del ADR-0008.

Esto confirma que el problema del thinking no es exclusivo de las etapas con esquema JSON: incluso con
un presupuesto 6-12 veces más generoso que `Planificador`/`VerificadorGrounding`, el resultado depende
de cuánto tarde el modelo en terminar de "pensar" para esa pregunta puntual — no determinista, no
predecible por adelantado, y sin ninguna garantía de que el presupuesto alcance.

### Conclusión de la sesión 18

**`qwen3.5:4b` queda descartado sin integrar el fix.** El pensamiento nativo, activado por defecto y sin
forma de suprimirlo con los mecanismos que el pipeline ya usa (`think` a nivel de body,
`chat_template_kwargs`), rompe las dos etapas de salida JSON forzada de forma sistemática con los
presupuestos de tokens reales de este pipeline (hallazgos 74-75) — el modo de falla más severo de los
trece candidatos probados en esta investigación, porque con la puerta de relevancia en su valor de
producción rechazaría cada pregunta, no solo las de control. Es, otra vez, el mismo patrón estructural
del hallazgo 7 (sesión 2): ni el tamaño en disco, ni el `Intelligence Index`, ni las capacidades
declaradas por `ollama show` predicen este comportamiento — solo probarlo contra las llamadas reales de
salida forzada lo revela.

La diferencia con los candidatos anteriores es que esta vez sí se encontró un fix concreto y barato
(hallazgo 76: `reasoning_effort:"none"` en el `extraBody` ya existente) que en teoría resuelve el
problema de raíz sin gramáticas GBNF a mano ni un fork de llama.cpp — mucho más simple que el camino que
exigió integrar Bonsai (ADR-0009). Queda como candidato a retomar, no cerrado por completo: antes de
decidir si vale la pena integrarlo hace falta (a) agregar `reasoning_effort` al `extraBody` de los tres
componentes `*OpenAi`, (b) confirmar que el `Planificador` con el prompt completo (catálogo real,
salvedades código-vs-documentación) elige bien con el pensamiento apagado, y (c) medir la latencia real
del pipeline completo con esta config, porque `qwen3.5:4b` con 47% GPU no promete ser más rápido que
Ministral solo por evitar el pensamiento.

Estado del entorno al cierre: `qwen3.5:4b` queda descargado en `kb-ollama` (3.4 GB en disco) sin
borrar, junto a los doce modelos de sesiones anteriores. El `kb-api-test` temporal (puerto 8081) se
eliminó al terminar. `kb-api` real sigue en el perfil Ministral (`compose.ministral.yml`,
`tope-por-documento=20`, `Reformulador` condicional del hallazgo 72), sin cambios — nunca se tocó
durante esta sesión. No se descargó `qwen3.6` (no existe una variante de ~4B) ni
`empero-ai/Qwen3.8-4B` (descartado por análisis, sin evidencia propia en vivo).

## Sesión 19: `gemma4:e2b` descartado sin completar la descarga — el paquete real pesa 7.2 GB, no ~2 GB

Candidato propuesto fuera de esta serie de sesiones (comparación externa de velocidad contra Ministral,
sin evidencia propia todavía) como alternativa "chica y rápida". Antes de gastar tiempo probándolo contra
los tres roles reales del pipeline, se aplicó el mismo filtro que ya usó la sesión 4 y descartó a
`qwen3.6:4b` en la sesión 18: confirmar primero que el modelo entra en la VRAM real de esta GPU.

### Hallazgo 78: el tag existe en la librería oficial de Ollama, pero el manifiesto declara 7.2 GB de descarga — se aborta antes de completarla

Con `kb-api`, `kb-llama-server` (Ministral) y `kb-docling-serve` corriendo en paralelo (stack real, no un
entorno limpio), `nvidia-smi` medía **1594 MiB libres de 4096 MiB** al momento de lanzar la prueba —
bastante menos que los ~3.3-3.9 GB "en frío" que documentaron las sesiones 1 y 5, porque el resto del
stack y el escritorio de Windows ya venían consumiendo VRAM.

`docker exec kb-ollama ollama pull gemma4:e2b` sí resolvió el tag (no es un nombre inventado), pero el
progreso reportó un tamaño total de **7.2 GB** para la primera capa (`4e30e2665218`) — más grande que
cualquier candidato de ≤4B probado hasta ahora en esta investigación, incluido el más pesado
(`gemma3:4b-it-qat`, 4.0 GB cargado, hallazgo 6). Sin llegar a completar la descarga ni acercarse al
punto de poder medir `ollama ps`, ya es evidencia suficiente de que no entra ni parcialmente sin un
offload masivo a CPU — el mismo motivo que descartó a `gemma4:26b`/`qwen3-coder:30b` sin probarlos
(tamaño total, no parámetros activos, es lo que hay que cargar en memoria).

Se abortó la descarga a los ~280 MB (~4% del total): se cortó la tarea del harness y, al notar que el
proceso `ollama pull` seguía vivo dentro del contenedor pese a eso (mismo patrón operativo que el
hallazgo 60 — un "killed" del harness no garantiza que el proceso real haya muerto, aquí para un `docker
exec` en vez de una tarea de Windows), se lo mató explícitamente con `kill` dentro de `kb-ollama` para no
seguir gastando ancho de banda ni disco en un candidato ya descartado por tamaño.

Hipótesis no verificada de por qué pesa tanto: la línea "Gemma 4" de Google (2026) se describe en fuentes
externas como una arquitectura "unificada" que empaqueta encoders de visión/audio incluso en el tier más
chico (E2B), a diferencia de `gemma3:4b` (solo texto). No se abrió el manifiesto capa por capa para
confirmarlo — es la explicación más consistente con el tamaño observado, no un hecho verificado contra
este pipeline.

### Conclusión de la sesión 19

**`gemma4:e2b` queda descartado sin llegar a probar ningún rol del pipeline** (Planificador,
VerificadorGrounding, Sintetizador) — el filtro de VRAM de la sesión 4 lo elimina antes de esa etapa,
igual que pasó con `qwen3.6:4b` en la sesión 18. No es un candidato viable para esta GPU
independientemente de cuántos parámetros "activa" por token, porque el paquete completo (7.2 GB) no cabe
en los ~4 GB nominales de la T600 bajo ningún reparto CPU/GPU razonable.

Estado del entorno al cierre: la descarga parcial de `gemma4:e2b` quedó cancelada y no aparece en
`docker exec kb-ollama ollama list`. Ningún archivo de compose, `.env` ni el `kb-api` real se tocaron —
el resto del stack (`kb-api`, `kb-db`, `kb-llama-server`, `kb-docling-serve`, `kb-ollama`) siguió
corriendo sin interrupción durante toda la prueba.

## Sesión 20: se integra el fix de `qwen3.5:4b` (hallazgo 76) en un perfil propio, sin tocar producción

Retoma el pendiente que dejó abierto la sesión 18: `reasoning_effort:"none"` en el `extraBody` de los
tres componentes `*OpenAi`, más un perfil de compose dedicado para no competir con `compose.ministral.yml`
(el perfil real de producción, sin cambios).

### Cambios de código

- `PlanificadorOpenAi.java`, `VerificadorGroundingOpenAi.java`, `SintetizadorOpenAi.java`: se agregó
  `"reasoning_effort", "none"` al `extraBody` que ya llevaba `repeat_penalty` en los tres, mismo patrón
  que ya usaba ese parámetro (no es parte de la API oficial de OpenAI). Aplica sin condicionar por
  perfil — un campo extra que Ministral/Bonsai (sin "thinking") ignoran, mismo argumento que ya vale
  para `repeat_penalty` en esos dos backends.
- `compose.qwen35.yml` (nuevo): perfil de Ollama para `qwen3.5:4b`, mismo mecanismo que
  `compose.ministral.yml` (`SPRING_AI_OPENAI_BASE_URL: http://ollama:11434/v1`, sin `llama-server`
  aparte). Copia como punto de partida los mismos recortes de contexto de Ministral
  (`KB_EXPANDIR_VECINOS`, `KB_MAX_FRAGMENTOS_CONTEXTO`, `KB_RECUPERACION_TOP_RERANK`,
  `KB_RECUPERACION_TOPE_POR_DOCUMENTO=20`), pero deja anotado explícitamente que el `num_ctx=4096` que
  motivó esos valores para Ministral (hallazgo 56, confirmado con `ollama ps`) **no se remidió para
  `qwen3.5:4b`** — arquitectura distinta (`qwen35` vs `Ministral3ForCausalLM`), sin garantía de que
  Ollama le aplique el mismo default pese a su contexto nativo de 262144.
- `Makefile`: `up-qwen35`/`down-qwen35`/`pull-qwen35`, mismo patrón que los targets de Ministral.

Verificado: `./mvnw clean compile` sin errores, `docker compose -f compose.yml -f compose.gpu.yml -f
compose.qwen35.yml config --quiet` valida sin errores de sintaxis. No se levantó el perfil contra el
pipeline real en esta sesión.

### Lo que esta sesión NO hizo — sigue pendiente antes de considerar `qwen3.5:4b` un candidato viable

1. **Confirmar el fix con el prompt completo del `Planificador`**: la sesión 18 solo probó un prompt
   simplificado (sin el catálogo real de herramientas ni las salvedades código-vs-documentación) — sigue
   sin evidencia de que elija bien las herramientas con el prompt de producción.
2. **Medir `num_ctx` real** con `docker compose exec ollama ollama ps` después de una consulta, para
   confirmar o corregir los valores de recorte de contexto copiados de Ministral.
3. **Correr el piloto de evaluación** (`eval-100-preguntas/`) contra el pipeline real para tener un
   número de precisión comparable al 85.3% de Ministral (sesión 17) — nada de esto se midió todavía con
   `qwen3.5:4b`.
4. **Medir latencia end-to-end del pipeline completo**, no solo llamadas aisladas — 47% GPU (peor reparto
   que Ministral) significa que evitar el "thinking" no garantiza ser más rápido en total.

Estado del entorno al cierre: `kb-api` real sigue en el perfil Ministral (`compose.ministral.yml`), sin
tocar. `compose.qwen35.yml` es nuevo y no se levantó. El código de `*OpenAi` sí cambió (afecta a
cualquier perfil que se use a partir de ahora, incluido Ministral/Bonsai, de forma inocua) — recompilado
y verificado, pendiente de que `kb-api` en producción se reconstruya con la próxima build normal.

## Sesión 21: primera corrida real del perfil `qwen35` contra el pipeline completo — el mejor resultado cualitativo de toda la investigación, con un gap nuevo encontrado y corregido en vivo

Retoma el pendiente #1 de la sesión 20 (confirmar el fix con el prompt completo, no el simplificado de
la sesión 18). A diferencia de todas las sesiones anteriores, esta corrió contra `kb-api` real (no un
`kb-api-test` aislado en otro puerto) porque, al momento de empezar, **el stack completo ya no existía**
— se había reiniciado entre sesiones (contenedores `kb-*` ausentes de `docker ps -a`, sin relación con
esta investigación). Los modelos de Ollama sí persistieron en `${KB_DATA_DIR}/ollama` (bind mount, 36 GB
en disco). Se levantó todo de nuevo con
`docker compose -f compose.yml -f compose.gpu.yml -f compose.qwen35.yml up -d --build`, con la puerta de
relevancia en su valor de producción (`KB_UMBRAL_RELEVANCIA_HABILITADO=true`, sin overridear) — a
diferencia de la mayoría de sesiones anteriores (2-18), que la apagaban a propósito para comparar
síntesis en aislamiento.

### Hallazgo 79: `num_ctx=4096` confirmado para `qwen3.5:4b` — la suposición de `compose.qwen35.yml` era correcta

`docker exec kb-ollama ollama ps` durante la primera consulta real: `qwen3.5:4b`, 3.8 GB, **53%/47%
CPU/GPU, num_ctx=4096** — exactamente lo que `compose.qwen35.yml` había dejado como suposición sin
confirmar (comentario de la sesión 20: "no se remidió para qwen3.5:4b"). Cierra el pendiente #2 de esa
sesión: los recortes de contexto copiados de Ministral (`KB_EXPANDIR_VECINOS`, `KB_MAX_FRAGMENTOS_CONTEXTO`,
`KB_RECUPERACION_TOP_RERANK`) están bien fundamentados para este modelo también, no solo por analogía.

### Hallazgo 80: pregunta relevante ("cómo se despliega el servicio") — Planificador correcto con el prompt real, y la mejor citación medida contra este caso en toda la investigación

Plan: `{"herramientas":["search_unified","search_docs"],"razon":"pregunta de despliegue"}` — correcto,
sin la confusión código-vs-documentación que el prompt real (con catálogo completo y salvedades)
advierte explícitamente. A diferencia de la prueba simplificada de la sesión 18 (hallazgo 76, prompt sin
catálogo real), esta sí usó `PlanificadorOpenAi` de producción tal cual — cierra el pendiente #1 de la
sesión 20.

Síntesis: prosa correcta y completa, **una sola cita `[1]` pegada al final de cada afirmación puntual**,
e ignoró por completo los 5 fragmentos irrelevantes de `jls25.pdf` que trajo la búsqueda (sobre
`ServiceLoader`, texto legal de la licencia) sin citarlos por citar — el mismo patrón que la sesión 5
había medido como "la mejor citación de la investigación" para Bonsai (hallazgo 12), aquí igualado.
Ningún fallback ni advertencia en los logs. Latencia total: **344 s** (dos herramientas en paralelo,
~38 s cada una, más planificador + síntesis).

### Hallazgo 81: pregunta de control ("explícame cómo usar Java 25") — interceptada por la puerta de relevancia, cero alucinación

A diferencia de los doce candidatos de las sesiones 2-9 y de `qwen2.5:3b`/`llama3.2:3b` (que alucinaban
pegando las instrucciones de despliegue como respuesta, con la puerta apagada a propósito en esas
pruebas), esta corrida usó la puerta en su valor real de producción y el resultado fue el mensaje
`MENSAJE_SIN_INFORMACION` ("No encontré información suficientemente relevante..."), `citas: []` — el
mismo comportamiento protegido que el hallazgo 16 ya había confirmado con `gemma3:4b`, ahora también con
`qwen3.5:4b`. El mejor fragmento recuperado tuvo `rerank=0.918`, muy por debajo del `techo-confianza=8.0`
heredado de Ministral. Latencia: **192 s**. Sin fallback ni advertencias del Planificador/VerificadorGrounding
en los logs — el `reasoning_effort:none` de ambos componentes sostuvo la salida JSON forzada también en
este caso.

### Hallazgo 82: gap nuevo encontrado en vivo — `ReformuladorOpenAi` (sesión 17) había quedado sin el fix, porque no existía cuando se escribió la sesión 18

La pregunta de control disparó el `Reformulador` (la búsqueda inicial no llegó a `SUFICIENTE`) y el log
mostró exactamente el mismo síntoma de los hallazgos 74/75: `Fallo al reformular la consulta, se usa la
pregunta original: ... MismatchedInputException: No content to map due to end-of-input` — el pensamiento
se comía el `maxTokens(120)` sin dejar nada para el JSON de `Reformulacion`. Benigno en este caso (el
`catch` ya usa la pregunta original como respaldo, así que no rompió la consulta), pero desperdiciaba la
llamada entera. Motivo: `ReformuladorOpenAi.java` se agregó en la sesión 17 (docs de esa sesión,
hallazgo 70), **después** de que la investigación de `qwen3.5:4b` (sesión 18) ya hubiera cerrado — el
grep que armó la sesión 20 sobre `extraBody|repeat_penalty|reasoning_effort` sí lo habría encontrado,
pero el inventario de "los tres componentes `*OpenAi`" quedó desactualizado desde antes de esa sesión.

**Corregido en vivo dentro de esta misma sesión**: se agregó `extraBody(Map.of("reasoning_effort",
"none"))` a `ReformuladorOpenAi.java` (sin `repeat_penalty`, a diferencia de los otros tres — sin
evidencia propia de que este componente lo necesite), se recompiló, se reconstruyó la imagen
(`docker compose ... up -d --build api`) y se repitió una pregunta que dispara el `Reformulador` a
propósito ("que es el autoboxing en Java", el mismo ejemplo canónico del hallazgo 70).

### Hallazgo 83: con el fix del `Reformulador` aplicado, la pregunta de autoboxing responde correcto y sin fallback

`consultaReformulada: "autoboxing"` (el `Reformulador` sí se activó y devolvió algo distinto al texto
original, disparando una segunda ronda de búsqueda). Síntesis: explicación correcta de autoboxing/unboxing
citando `jls25.pdf` (`[2]` y `[3]`, pegados al final de cada afirmación, terminología técnica precisa
en español latinoamericano neutro). **Sin ningún "Fallo al reformular" en los logs** — el gap del
hallazgo 82 queda cerrado. Latencia: 428 s (incluye la ronda de búsqueda extra que dispara el
`Reformulador`).

### Conclusión de la sesión 21

**Los tres casos probados contra el pipeline real, con la puerta de relevancia en su valor de
producción, salieron limpios**: pregunta relevante (citación impecable), pregunta de control (rechazo
correcto, cero alucinación) y pregunta que dispara el `Reformulador` (una vez corregido el gap del
hallazgo 82, también limpia). Es la primera vez en la investigación que un candidato pasa los tres casos
canónicos sin ningún defecto medido — ni sobre-citación (`gemma3:4b`), ni pegado de texto crudo
(`granite4.1:3b`/`phi4-mini:3.8b`/`qwen2.5:3b`), ni alucinación de contenido nuevo (`llama3.2:3b`), ni
fallo de convergencia en salida forzada (`qwen3:4b`/`nanbeige4.1:3b`/`minicpm5`).

**Esto NO es todavía "listo para producción"**: son 3 preguntas, no el piloto de 100 preguntas que le
dio a Ministral su cifra de 85.3% de precisión (sesión 17). Las latencias (192-428 s por pregunta) son
altas y no se compararon con Ministral bajo la misma carga de GPU/contención — el reparto 47% GPU sigue
siendo peor que el 100% de `qwen2.5:3b` o la carga completa de Ministral. El pendiente real para una
recomendación firme sigue siendo el piloto completo (`eval-100-preguntas/`), no descartado por esta
sesión sino reforzado: por primera vez hay evidencia cualitativa suficiente para justificar el costo de
correrlo.

Estado del entorno al cierre: **el stack queda arriba con el perfil `qwen35` activo** (`kb-api` real
sirviendo `qwen3.5:4b`, con el fix del `Reformulador` ya reconstruido en la imagen) — a diferencia de
todas las sesiones anteriores, que revertían a Ministral o apagaban todo al cerrar. `compose.ministral.yml`
sigue intacto y disponible (`make up-ministral` para volver). El código de `ReformuladorOpenAi.java`
quedó modificado junto a los tres de la sesión 20 — los cuatro componentes `*OpenAi` que llaman al LLM ya
tienen `reasoning_effort:none`.

## Sesión 22: buscando algo mejor que Ministral — `nemotron-mini:4b` es más rápido que `qwen3.5:4b` pero reproduce el defecto de "texto pegado" que ya había descartado a otros tres candidatos

Pregunta directa del usuario: ¿hay una alternativa mejor que Ministral? Búsqueda de candidatos nuevos no
probados en las 21 sesiones anteriores (WebSearch, agosto 2026): `SmolLM3-3B` (1.92 GB, pero con modo
"reasoning" nativo — mismo riesgo estructural que ya complicó a `qwen3.5:4b`, no probado por eso) y
`nemotron-mini:4b` (NVIDIA, Ollama oficial, 2.7 GB en disco). Se eligió este último para probar primero:
`ollama show nemotron-mini:4b` declara capacidades `completion` y `tools` **sin `thinking`** — el primer
candidato desde `qwen2.5:3b` (sesión 4) sin ese riesgo estructural de origen, y contexto 4096 confirmado
directo (no una suposición heredada como con `qwen3.5:4b`).

Se creó `compose.nemotron.yml` (mismo patrón que los otros perfiles de Ollama, sin cambios de código —
no hace falta `reasoning_effort` para un modelo sin "thinking") y se probó contra el pipeline real,
mismo protocolo que la sesión 21: puerta de relevancia en su valor de producción, `POST /api/ask` contra
`kb-api` real (el stack seguía arriba desde la sesión 21).

### Hallazgo 84: reparto CPU/GPU mejor que `qwen3.5:4b`, y más rápido en los dos casos — pero sigue por detrás de Ministral

`ollama ps`: 3.9 GB cargado, **40% CPU / 60% GPU** — mejor ajuste que `qwen3.5:4b` (53%/47%), aunque
peor que Ministral. Latencias contra el pipeline real:

| Caso | Ministral (sesión 10) | `qwen3.5:4b` (sesión 21) | `nemotron-mini:4b` (esta sesión) |
|---|---|---|---|
| Pregunta relevante | 177 s | 344 s | **281 s** |
| Pregunta de control | 141 s | 192 s | **186 s** |

Más rápido que `qwen3.5:4b` en los dos casos, consistente con no pagar el impuesto de "thinking" y con
un modelo más chico (4.2B vs 4.7B) — pero todavía claramente más lento que Ministral en ambos.

### Hallazgo 85: la pregunta relevante reproduce el defecto de "texto pegado sin citas" que ya había descartado a tres candidatos — sin ningún marcador `[n]`, y la respuesta queda incompleta

Plan del Planificador correcto (`["search_docs"]`, razón "pregunta de despliegue"). Pero la síntesis:

> "Para desplegar el servicio se necesita Docker Desktop con el motor iniciado. En Windows, además hay
> que copiar wslconfig.example a .wslconfig para darle memoria a WSL2."

Casi texto literal de `despliegue.md` (compárese con el fragmento fuente, hallazgo 80), **sin un solo
marcador `[n]`** pese a que el prompt de sistema lo exige explícitamente, y **incompleta**: se corta
después del segundo párrafo del fragmento fuente, sin mencionar `.env.example`, `docker compose up -d`
ni la detección automática de GPU — los otros dos pasos que sí estaban en el mismo fragmento citado. Es
el mismo patrón de "pegado de texto crudo" que ya había descartado a `granite4.1:3b`, `phi4-mini:3.8b`
y `qwen2.5:3b` (hallazgos 3, 8 y 9) — la ausencia de "thinking" evita el modo de falla de convergencia
JSON (hallazgo 7), pero no garantiza una síntesis de calidad en texto libre.

### Hallazgo 86: la pregunta de control sí funciona bien — rechazo correcto, cero alucinación, con una imprecisión menor del Planificador

`MENSAJE_SIN_INFORMACION`, `citas: []` — igual que Ministral, `gemma3:4b` (hallazgo 16) y `qwen3.5:4b`
(hallazgo 81). El plan agregó `who_knows` de más (`["search_docs","search_unified","who_knows"]`) sin
necesitarlo para esta pregunta — imprecisión menor, no una falla (herramientas de más solo cuestan
latencia, mismo tipo de defecto que el hallazgo 15 de Bonsai), no impidió el rechazo correcto.

### Conclusión de la sesión 22

**`nemotron-mini:4b` no es una alternativa mejor que Ministral.** Gana en dos ejes puntuales (sin riesgo
de "thinking", más rápido que `qwen3.5:4b`) pero pierde en el eje que más importa para este pipeline: la
calidad de síntesis. El defecto de citación medido (hallazgo 85) lo coloca en el mismo grupo que ya
había descartado a otros tres candidatos, y sigue siendo más lento que Ministral en los dos casos
comparables.

**Balance de las tres sesiones de esta búsqueda (20-22), respondiendo la pregunta original del
usuario ("busca una alternativa mejor a Ministral")**: ninguno de los dos candidatos nuevos lo supera en
conjunto.

- `qwen3.5:4b`: mejor citación medida de toda la investigación (hallazgo 80, empata con Bonsai), pero
  ~2x más lento que Ministral en los dos casos comparables.
- `nemotron-mini:4b`: más rápido que `qwen3.5:4b` pero sigue por detrás de Ministral, y con un defecto
  de síntesis real (texto pegado sin citas, respuesta incompleta).
- Diecisiete candidatos probados en 22 sesiones, y **ninguno desplaza a Ministral** como el mejor
  balance velocidad/calidad para esta GPU de 4 GB — la conclusión de la sesión 4 ("no hay alternativa
  que entre 100% en VRAM y pase la barra de calidad") se extiende ahora también al eje de velocidad.

`SmolLM3-3B` queda sin probar (riesgo de "thinking" no descartado) — candidato a investigar si se retoma
esta búsqueda, con la misma metodología: confirmar capacidades con `ollama show` antes de invertir tiempo
en probarlo contra el pipeline.

Estado del entorno al cierre: el stack sigue arriba con el perfil `nemotron` activo (`kb-api` sirviendo
`nemotron-mini:4b`). Dado que ni `qwen3.5:4b` ni `nemotron-mini:4b` superaron a Ministral, la
recomendación es volver al perfil de producción (`make up-ministral`) — pendiente de que el usuario lo
confirme, no se ejecutó en esta sesión. `compose.nemotron.yml` y los targets `up-nemotron`/`down-nemotron`/
`pull-nemotron` del Makefile quedan como nuevo perfil experimental disponible, igual que `qwen35`.

## Sesión 23: `SmolLM3-3B` — el mejor reparto de VRAM de los 18 candidatos, pero la peor síntesis medida en toda la investigación

Retoma el pendiente que cerraba la sesión 22: el candidato con "thinking" nativo que había quedado sin
probar por el riesgo estructural ya conocido. `ollama show hf.co/ggml-org/SmolLM3-3B-GGUF:Q4_K_M`
confirma capacidades `tools`, `thinking`, `completion` (arquitectura `smollm3`, 3.08B parámetros,
contexto nativo 65536, 1.9 GB en disco) — mismo riesgo que `qwen3.5:4b`. Se probó igual porque el fix
`reasoning_effort:none` (hallazgo 76) ya está cableado sin condicionar por modelo en los cuatro
componentes `*OpenAi`: si Ollama no lo respetara para esta arquitectura, se vería de inmediato como
fallback del Planificador en los logs, sin necesitar código nuevo para descartarlo. Se creó
`compose.smollm3.yml` (mismo patrón que los otros perfiles de Ollama) y se probó contra el pipeline real,
mismo protocolo que las sesiones 21-22.

### Hallazgo 87: `reasoning_effort:none` sí funciona para esta arquitectura también, y el reparto de VRAM es el mejor medido en 18 candidatos

`ollama ps`: 2.6 GB cargado, **11% CPU / 89% GPU**, `num_ctx=4096` — el mejor ajuste de GPU de todos los
candidatos de las sesiones 20-23, mejor incluso que `qwen2.5:3b` (100% GPU pero VRAM total más alta) en
términos de dejar margen a la vez que usa la GPU casi por completo. Sin ningún "Fallo al planificar" ni
"Fallo al verificar" en los logs — el fix genérico (sin condicionar por modelo) sostuvo la salida JSON
forzada también en esta arquitectura, tercera confirmación después de `qwen3.5:4b` (sesión 21) y, por
ausencia total de "thinking", el caso trivial de `nemotron-mini:4b` (sesión 22). Latencia: **251 s** —
más rápido que `qwen3.5:4b` (344s) y `nemotron-mini:4b` (281s), aunque sigue por detrás de Ministral
(177s).

### Hallazgo 88: la síntesis es la peor medida en toda la investigación — pegó el fragmento crudo completo, con un encabezado que imita una cita real sin serlo

Plan del Planificador correcto (`["search_unified"]`), aunque con una `razon` que no respeta el límite
de 6-8 palabras del prompt (una oración completa con punto final — imprecisión menor, no una falla).
La síntesis, en cambio, es un caso nuevo y peor que el "pegado de texto crudo" ya visto en
`granite4.1:3b`/`phi4-mini:3.8b`/`qwen2.5:3b`/`nemotron-mini:4b` (hallazgos 3, 8, 9, 85):

```
[1] despliegue.md (file:///vault/documentos/despliegue.md)
Para desplegar el servicio se necesita Docker Desktop con el motor iniciado. En Windows, además hay
que copiar wslconfig.example a .wslconfig para darle memoria a WSL2.

1. Copia .env.example a .env.
2. Corre docker compose up -d. El make up detecta la GPU sola.
...
```

No hay una sola oración de prosa propia: el modelo reprodujo el fragmento completo tal cual llega en el
contexto, incluido el encabezado `[1] despliegue.md (uri)` que **imita el formato de una cita real sin
serlo** — el prompt pide `[n]` pegado al final de cada afirmación puntual, no un bloque de metadatos al
inicio seguido de una copia literal. Es cualitativamente peor que los otros cuatro candidatos con este
defecto: ninguno de ellos había llegado a reproducir el propio formato `[n] titulo (uri)` como si fuera
parte de la respuesta.

### Conclusión de la sesión 23

**`SmolLM3-3B` queda descartado.** Resuelve los dos riesgos estructurales que más candidatos habían
eliminado en esta investigación (VRAM — el mejor reparto medido — y "thinking" sin apagar — el fix
genérico funcionó sin ajuste), pero falla en el eje que de verdad importa para este pipeline: la síntesis
en español no es prosa, es una copia literal del contexto con metadatos de cita falsos. Ni el mejor
ajuste de VRAM ni la velocidad (251s, la mejor de los tres candidatos nuevos) compensan eso.

**Cierra, por ahora, la búsqueda de alternativa a Ministral iniciada en la sesión 20**: dieciocho
candidatos probados en 23 sesiones, ninguno desplaza a Ministral como mejor balance velocidad/calidad
para esta GPU de 4 GB. El patrón que emerge de las sesiones 20-23 en particular: los candidatos sin
"thinking" o con el fix aplicado (`nemotron-mini:4b`, `SmolLM3-3B`) evitan el modo de falla de
convergencia JSON, pero eso no garantiza nada sobre la calidad de la síntesis en texto libre — son ejes
independientes, ninguno de los dos predice al otro.

Estado del entorno al cierre: el stack sigue arriba con el perfil `smollm3` activo (`kb-api` sirviendo
`hf.co/ggml-org/SmolLM3-3B-GGUF:Q4_K_M`). `compose.smollm3.yml` queda como nuevo perfil experimental
descartado (documentado, no eliminado). Recomendación reiterada: volver a `make up-ministral`, pendiente
de confirmación del usuario.

## Sesión 24: se agrega `SintetizadorEstructuradoOpenAi` (vía forzada JSON en vez de prosa libre) y se prueba contra el defecto exacto del hallazgo 88 — mejora medible

A pedido del usuario, se implementa la vía que la sesión 3 había dejado anotada como no explorada: en vez
de pedirle al modelo prosa libre con `[n]` incrustado a mano, forzar la síntesis como salida JSON
estructurada (`{afirmaciones: [{texto, fuente}]}`), el mismo patrón que ya usan `Planificador` y
`VerificadorGrounding`. Implementado como componente **nuevo y opt-in**, sin tocar el `Sintetizador` de
producción:

- `SintetizadorEstructuradoOpenAi.java` (nuevo): llama al LLM una sola vez (bloqueante, con
  `useProviderStructuredOutput()`), arma el texto final uniendo cada afirmación con su `[n]` pegado al
  final, y lo devuelve como un `Flux` de un solo elemento -- cumple el contrato de la interfaz
  (`Flux<String> sintetizar(...)`) sin tocar `Orquestador` ni el adaptador SSE. Trade-off documentado en
  el propio javadoc: la UI en streaming pierde el efecto palabra-por-palabra (la respuesta completa llega
  de una vez), sin medición propia todavía de si eso es aceptable para el caso de uso real.
- `SintetizadorOpenAi.java` (existente): sin cambios de comportamiento -- se le agregó
  `@ConditionalOnProperty(..., havingValue = "false", matchIfMissing = true)` para que siga siendo el
  único bean activo salvo que se active la alternativa explícitamente.
- Propiedad nueva `kb.llm.sintesis-estructurada.habilitada` (`KB_LLM_SINTESIS_ESTRUCTURADA_HABILITADA`,
  default `false`) -- ningún perfil ni `.env` existente cambia de comportamiento sin overridearla.
- Cada `Afirmacion` lleva una sola fuente (`int`, no una lista): el esquema mismo impide por construcción
  la sobre-citación que tuvo `gemma3:4b` (hallazgo 6) -- si una idea depende de dos fragmentos, el modelo
  tiene que partirla en dos afirmaciones, no hay campo para poner dos números.

`./mvnw test` completo (123 tests, 0 fallos) confirmó que no rompió nada existente antes de probarlo en
vivo.

### Hallazgo 89: error operativo real -- modificar código Java y reiniciar el contenedor sin `--build` deja corriendo el jar viejo, sin ningún aviso

Al activar `KB_LLM_SINTESIS_ESTRUCTURADA_HABILITADA=true` contra el perfil `smollm3` (sesión 23) con
`docker compose ... up -d api` (sin `--build`), la respuesta salió sorprendentemente rápida (56s, contra
251s de la sesión 23) y con prosa bien formada -- pero **sin ningún marcador `[n]` real** (solo un `[1]`
suelto en medio de la primera oración, estilo distinto al que produce el código nuevo). La sospecha se
confirmó copiando el jar en ejecución del contenedor (`docker cp` + inspección con
`System.IO.Compression.ZipFile`, ya que la imagen runtime no trae `unzip` ni `jar`): **la clase
`SintetizadorEstructuradoOpenAi` no estaba en el jar** -- el contenedor seguía corriendo la imagen
compilada antes de escribir el código nuevo (sesión 21), y `docker compose up -d` sin `--build` no
reconstruye nada si el `Dockerfile`/contexto no cambió de forma que Compose detecte. La variable de
entorno nueva no tenía ningún efecto real: corrió el `SintetizadorOpenAi` de siempre, y la latencia/
calidad distintas de la sesión 23 fueron variación normal de muestreo del mismo modelo, no evidencia de
nada. **Lección operativa**: cualquier cambio de código Java exige `--build` explícito antes de la
siguiente prueba -- `docker compose up -d` sin ese flag no es una señal confiable de "ya corre lo nuevo".

### Hallazgo 90: con la imagen reconstruida de verdad, la síntesis estructurada resuelve el defecto exacto del hallazgo 88 -- y es más rápida

Reconstruido con `--build` y verificado que la clase sí está en el jar corriendo (mismo método de
`docker cp` + `ZipFile`), se repitió la pregunta de despliegue contra `SmolLM3-3B` con la puerta de
relevancia en su valor de producción:

```
Para desplegar el servicio se necesita Docker Desktop con el motor iniciado. En Windows, además hay
que copiar wslconfig.example a .wslconfig para darle memoria a WSL2. [1] Se recomienda copiar
.env.example a .env antes de desplegar el servicio. [1] El comando 'docker compose up -d' se utiliza
para iniciar el servicio en modo detallado, detectando la GPU sola si está presente. [1]
```

Comparado con el hallazgo 88 (mismo modelo, mismo perfil, síntesis en texto libre):

| | Hallazgo 88 (texto libre) | Hallazgo 90 (estructurada) |
|---|---|---|
| Formato de cita | ninguno real (un encabezado `[1] titulo (uri)` que imita una cita) | `[1]` pegado al final de cada afirmación, correcto |
| Contenido | fragmento crudo, cortado a la mitad | tres afirmaciones, cubre despliegue + `.env` + `docker compose up -d` |
| Prosa propia | ninguna (copia literal) | sí, aunque la primera afirmación sigue cerca del fragmento fuente |
| Latencia | 251 s | **104 s** |
| Fallos/reintentos | ninguno | ninguno (el esquema anidado `afirmaciones: [{texto, fuente}]` no le costó más que el esquema plano de `Planificador`) |

Sin ningún "Fallo al planificar"/"Fallo al verificar" en los logs -- el esquema anidado no rompió nada
que el esquema plano de `PlanDeHerramientas` ya sostenía.

### Conclusión de la sesión 24

**La vía de salida estructurada sí mejora el defecto de "texto pegado" en este candidato puntual**:
mejor formato de citación, contenido más completo, y más rápida. No es una validación completa
(una sola pregunta, no las dos del protocolo habitual ni el piloto de 100) y la primera afirmación
todavía queda bastante cerca del texto fuente en vez de una paráfrasis genuina -- pero es evidencia
concreta de que la hipótesis de la sesión 3 tenía fundamento: el problema de varios candidatos no era
necesariamente "no saben redactar", sino "en texto libre con instrucciones de formato finas, el camino
de menor esfuerzo es copiar". Queda pendiente, si se retoma: la pregunta de control (validar que sigue
sin alucinar), probar esta misma opción con `nemotron-mini:4b` (el otro candidato con el mismo defecto),
y medir el trade-off real de perder el streaming palabra-por-palabra en la UI.

Estado del entorno al cierre: el stack sigue arriba con el perfil `smollm3` y
`KB_LLM_SINTESIS_ESTRUCTURADA_HABILITADA=true` activo -- no es la configuración de producción.
`compose.smollm3.yml` quedó con la variable agregada (default `false`, overrideable por shell, sin
cambiar su comportamiento si no se pasa explícitamente). Recomendación reiterada: volver a
`make up-ministral` para producción; este perfil queda como banco de pruebas de la opción nueva.

### Hallazgo 91: la pregunta de control cierra la validación de las dos preguntas canónicas -- rechazo correcto, cero alucinación

Con la misma imagen reconstruida (hallazgo 90), se corrió "explícame cómo usar Java 25" contra
`SmolLM3-3B` + síntesis estructurada: `MENSAJE_SIN_INFORMACION`, `citas: []`, **186.5 s**, sin ningún
"Fallo al planificar"/"Fallo al verificar" en los logs. Igual que Ministral, `gemma3:4b` (hallazgo 16) y
`qwen3.5:4b` (hallazgo 81) -- la puerta de relevancia interceptó la pregunta antes de que llegara a
síntesis, así que esta prueba puntual no valida la resistencia a alucinar del
`SintetizadorEstructuradoOpenAi` en sí (nunca se lo llamó para este caso) -- valida que el resto del
pipeline (Planificador, recuperación, `VerificadorGrounding`) sigue funcionando sin regresión con esta
opción activa.

Con las dos preguntas canónicas resueltas, la comparación de latencia contra Ministral (sesión 10,
177s/141s) queda:

| Pregunta | Ministral | `SmolLM3-3B` + síntesis estructurada |
|---|---|---|
| Relevante | 177 s | **104 s** |
| Control | **141 s** | 186 s |
| Promedio | 159 s | **145 s** |

Con esta muestra de dos preguntas, el promedio queda del lado de `SmolLM3-3B` + síntesis estructurada --
pero es una comparación de una sola pregunta por lado contra un modelo (Ministral) que sí tiene un
piloto de 100 preguntas detrás (85.3%, sesión 17) y un historial de 24 sesiones de ajuste fino. No es
evidencia suficiente para recomendar el cambio, solo para justificar que vale la pena medirlo en serio.

### Conclusión final de la sesión 24

**`SmolLM3-3B` + síntesis estructurada pasa las dos pruebas canónicas sin ningún defecto medido**:
citación correcta y completa en la pregunta relevante (hallazgo 90), rechazo limpio sin alucinación en
la de control (hallazgo 91), latencia competitiva con Ministral en esta muestra chica. Es, junto con
Ministral, el único candidato de los 18 probados en toda la investigación que pasa ambos casos sin un
defecto de síntesis, alucinación, o fallo de convergencia -- con la salvedad de que esto depende de una
característica (síntesis estructurada) que ningún otro candidato usó, y que tiene su propio costo no
medido (perder el streaming palabra-por-palabra en la UI, señalado desde el javadoc de
`SintetizadorEstructuradoOpenAi`).

**No reemplaza al piloto de 100 preguntas** como criterio para promover un cambio de producción -- eso
sigue pendiente, junto con medir el trade-off de UI y probar la misma opción con `nemotron-mini:4b` (el
otro candidato con el mismo defecto de "texto pegado" que la síntesis estructurada podría resolver
igual).

## Sesión 25: piloto de 10 preguntas (smoke10) con síntesis estructurada contra los ocho candidatos
## descartados en sesiones anteriores -- dos bugs reales de producción encontrados en el camino

Retoma el pendiente que cerraba la sesión 24: probar `SintetizadorEstructuradoOpenAi` contra un lote de
10 preguntas reales (no 2), y extenderlo a los candidatos que la investigación ya había descartado por
"texto pegado" o por no converger en salida JSON forzada, para ver si la vía de la sesión 24 los rescata
a escala. Mismo protocolo que las sesiones 20-24: perfil compose dedicado por modelo,
`KB_LLM_SINTESIS_ESTRUCTURADA_HABILITADA=true`, puerta de relevancia en su valor de producción, y
`eval-100-preguntas/ejecutar.js` con `EVAL_LIMITE=10 EVAL_SUFIJO=smoke10.<modelo>-estructurada` contra
`kb-api` real (nunca un `kb-api-test` aislado esta vez).

Perfiles compose nuevos creados en esta sesión, mismo patrón que los ya existentes:
`compose.granite41.yml`, `compose.phi4mini.yml`, `compose.qwen25.yml` (los tres candidatos de "texto
pegado" de las sesiones 2-9 que nunca habían tenido un perfil dedicado), y más adelante
`compose.nanbeige.yml`/`compose.minicpm5.yml` (los dos candidatos de "no converge" de la sesión 2).
`compose.bonsai.yml` y `compose.ministral.yml` predatan la propiedad `sintesis-estructurada.habilitada`
(sesión 24) y no la pasaban al contenedor -- se les agregó la misma línea
`KB_LLM_SINTESIS_ESTRUCTURADA_HABILITADA: ${KB_LLM_SINTESIS_ESTRUCTURADA_HABILITADA:-false}` que ya
tenían los perfiles más nuevos.

### Hallazgo 92: bug real de producción -- `SintetizadorEstructuradoOpenAi` podía dejar el cupo de
### consultas concurrentes agotado para siempre

Corriendo el smoke10 de `granite4.1:3b` y `qwen2.5:3b`, la mayoría de las preguntas respondía bien pero
alguna quedaba "servidor ocupado" de forma **permanente** -- ni reintentos ni esperar arreglaban nada,
solo `docker restart kb-api` liberaba el cupo. Causa raíz en `Orquestador.ejecutarEnStreaming()`: el
cupo (`Semaphore cupoConsultas`) se libera en un `.doFinally()` enganchado al `Flux<String>` que devuelve
`sintetizador.sintetizar(...)`. Pero `SintetizadorEstructuradoOpenAi.sintetizar()` (sesión 24) llamaba a
`chatClient.prompt().call().entity(...)` **de forma bloqueante y sincrónica, antes de construir el
`Flux`** -- si esa llamada lanzaba una excepción (por ejemplo, JSON truncado por quedarse sin
`maxTokens`, ver hallazgo 93), la excepción escapaba de `sintetizar()` antes de que existiera ningún
`Flux` al que engancharle el `doFinally()`. El cupo quedaba adquirido para siempre, sin ningún camino de
liberación. **Corregido** envolviendo el cuerpo del método en `Flux.defer(() -> { ... })`: ahora la
llamada bloqueante ocurre dentro del `Flux`, cualquier excepción se convierte en una señal `onError` que
sí dispara el `doFinally()`, y el cupo se libera siempre. Recompilado, `./mvnw test` (123 tests, 0
fallos) y reconstruida la imagen antes de seguir.

### Hallazgo 93: el JSON truncado por `maxTokens` insuficiente es un problema recurrente, no de un solo
### modelo -- subido dos veces en vivo (700 → 1500 → 4000)

`maxTokens(700)` (valor de partida de la sesión 24, "sin medición propia, margen de partida") truncó a
`qwen2.5:3b` a mitad de la 9ª afirmación (`Jackson.UnexpectedEndOfInputException`). Subido a 1500,
confirmado sin cortes en `granite4.1:3b`, `phi4-mini:3.8b`, `qwen2.5:3b` y `qwen3.5:4b` -- pero
`SmolLM3-3B` lo volvió a truncar, esta vez a mitad de la **23ª** afirmación (índice 22 del array), muy
por encima de lo que 1500 tokens de JSON pueden sostener. Subido a 4000, `SmolLM3-3B` completó las 10
preguntas en un solo intento. La cantidad de afirmaciones en que un modelo divide una respuesta no tiene
relación clara con la longitud real de la respuesta ni con el tamaño del modelo -- no hay forma de
predecir el presupuesto necesario sin medirlo en vivo. `Ministral 3B` truncó 2/10 preguntas con
`maxTokens=1500` (hallazgo 96) y **no se volvió a probar con 4000** -- queda pendiente confirmar si el
mismo aumento lo arregla, igual que arregló a `SmolLM3-3B`.

### Hallazgo 94: bug real, sin corregir -- cuando la síntesis estructurada falla, el cliente ve una
### conexión cortada en vez de un mensaje de error legible

Cada vez que el hallazgo 93 truncaba el JSON, los logs de `kb-api` mostraban un segundo warning
inmediatamente después de la excepción de Jackson: `HttpMessageNotWritableException: No converter for
[class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream'`. El manejador de
excepciones por defecto de Spring intenta escribir el cuerpo de error estándar (un `LinkedHashMap`
serializado a JSON) sobre una respuesta que ya está comprometida como `text/event-stream` -- falla, y
como la respuesta ya estaba comprometida, Spring solo registra el warning y listo. El resultado visible
para el cliente (y para quien esté mirando la UI en ese momento) es una conexión que se corta sin
explicación -- el mismo mensaje `"Se perdió la conexión con el servidor (¿Ollama no responde?)."` que la
UI ya usa para fallas de infraestructura reales, indistinguible de un truncamiento de JSON del lado del
servidor. No se corrigió en esta sesión (el hallazgo 92 ya resuelve el problema grave, la fuga del cupo;
este es un problema de experiencia -- el mensaje de error es engañoso, no una falla funcional) -- la vía
más simple sería un `.onErrorResume()` en el `Flux` de `SintetizadorEstructuradoOpenAi` que emita un
mensaje de error legible en vez de dejar que la excepción llegue cruda al controlador.

### Hallazgo 95: `granite4.1:3b`, `phi4-mini:3.8b` y `qwen2.5:3b` -- los tres candidatos originales de
### "texto pegado" (sesiones 2-9), limpios con síntesis estructurada

Con los fixes de los hallazgos 92-93 desplegados, los tres completaron 10/10 sin ningún error de
conexión ni truncamiento. Confirma a escala de 10 preguntas lo que la sesión 24 ya había medido en una
sola pregunta para `SmolLM3-3B`: la vía de salida JSON forzada resuelve el defecto de "pegar el fragmento
crudo" que estos tres candidatos tenían con `SintetizadorOpenAi` (texto libre). No todas las respuestas
fueron sustantivas -- ver la tabla comparativa del cierre de esta sesión, `VerificadorGrounding` rechazó
algunas de más en `phi4-mini:3.8b` y `qwen2.5:3b` -- pero ninguna de las respuestas que sí llegaron a
síntesis mostró el defecto original.

### Hallazgo 96: `qwen3.5:4b` -- 10/10 sin errores de infraestructura, pero con una respuesta completa en
### inglés y formato markdown en vez de prosa en español

Nueve de diez respuestas fueron correctas, en español, con citación `[n]` bien formada. La pregunta 2
("diferencia entre tipo primitivo y de referencia") devolvió una respuesta larga en **inglés**, con
encabezados markdown (`### 1. Primitive Types...`) y una tabla -- ignorando por completo la instrucción
del prompt de sistema ("Responde en español latinoamericano neutro") y el formato de citación esperado.
No se repitió en las otras 9 preguntas del mismo modelo, así que no es un problema sistemático de
`qwen3.5:4b` con este prompt -- es una falla puntual, pero real, que ningún otro candidato de esta
sesión reprodujo de esta forma exacta (inglés completo con markdown, no solo alguna palabra suelta).

### Hallazgo 97: `nemotron-mini:4b` -- abortado por el usuario a mitad de la corrida, inglés y citas
### fuera de rango desde la primera pregunta

Las dos primeras preguntas salieron en **inglés** (`"Primitive values do not share state..."`), y la
segunda citó `[0]` -- fuera del rango 1-indexado que el prompt de `SintetizadorEstructuradoOpenAi`
especifica explícitamente. El usuario abortó la corrida antes de completar las 10 (no vale la pena seguir
gastando tiempo de GPU en un candidato que ya falla los dos primeros casos de forma clara). Es el
candidato con peor comportamiento medido en esta sesión, empatado con `Bonsai` en no llegar a completar
el smoke10 -- a diferencia de `Bonsai`, que sí generaba contenido correcto pero muy lento, `nemotron-mini`
generaba rápido pero mal.

### Hallazgo 98: `Bonsai-8B` -- abortado por timeout estructural, no por un bug: el modelo es
### simplemente demasiado lento para sostener la síntesis estructurada dentro de 450s

Las dos primeras preguntas tardaron 8-9 minutos cada una y terminaron en `"Se perdió la conexión"` real
(no el hallazgo 94 -- esta vez `kb-llama-server` mismo reportó `cancel task`). Los logs de
`kb-llama-server` mostraron la causa exacta: la tarea se canceló a los 175s con el *prompt* todavía al
57% de ser procesado (13.21 tokens/s de *prompt eval*, antes de generar un solo token de salida) -- sumado
a la generación de hasta `maxTokens=4000` de JSON a los 4-5 tokens/s medidos en sesiones anteriores para
este modelo, el total supera con holgura los 450s de `KB_LLM_TIMEOUT` del perfil Bonsai. No es un fallo
de calidad ni de convergencia (los hallazgos 12 y 14 de la sesión 5 ya habían medido a Bonsai como el
mejor candidato en citación y en `VerificadorGrounding`) -- es que la síntesis estructurada, al exigir
generar el JSON completo en una sola llamada bloqueante en vez de transmitir token a token, es
estructuralmente peor candidata para un modelo tan lento que `SintetizadorOpenAi` (texto libre, que ya
sostenía respuestas completas dentro del mismo timeout, sesión 5-6). Subir `KB_LLM_TIMEOUT` podría
resolverlo en teoría, pero no se probó -- dos preguntas fallando igual ya es evidencia suficiente de que
el problema es estructural, no una racha de mala suerte.

### Hallazgo 99: `SmolLM3-3B` -- 10/10 limpio tras subir `maxTokens` a 4000 (hallazgo 93)

Confirma a escala de 10 preguntas lo que la sesión 24 ya había medido en 2: con presupuesto de tokens
suficiente, `SmolLM3-3B` sostiene la síntesis estructurada sin ningún defecto de formato ni de conexión.
Es, junto con `granite4.1:3b`, uno de los dos únicos candidatos de esta sesión sin ningún error medido.

### Hallazgo 100: `Ministral 3B` -- 8/10, dos preguntas truncadas por `maxTokens=1500` (sin remedir con
### el valor final de 4000)

Probado **antes** de que el hallazgo 93 subiera `maxTokens` a 4000 (el orden real de esta sesión fue
qwen2.5 → qwen3.5 → nemotron[abortado] → Bonsai[abortado] → Ministral → SmolLM3, y la segunda subida de
`maxTokens` se disparó recién al ver truncar a `SmolLM3-3B`). Las preguntas 2 y 10 fallaron las 5 veces
que se reintentaron, siempre con el mismo `UnexpectedEndOfInputException` -- un truncamiento
determinístico, no una falla de conexión real, aunque la UI lo muestra igual (hallazgo 94). Dado que
subir `maxTokens` a 4000 sí resolvió el mismo síntoma en `SmolLM3-3B`, es razonable esperar que también
resuelva estas dos en Ministral, pero **queda sin confirmar** -- el resultado de 8/10 que se reporta en
la tabla de cierre es con el valor viejo (1500), no el vigente en el código al terminar la sesión.

### Hallazgo 101: `Nanbeige4.1-3B` y `MiniCPM5-1B` -- el fix `reasoning_effort=none` de la sesión 18/20
### sí resuelve el problema de convergencia de la sesión 2, pero destapa defectos de calidad nuevos

Ambos candidatos habían quedado descartados en la sesión 2 (antes de que existiera el fix) por generar
razonamiento sin converger en las llamadas de salida JSON forzada -- más de 1800-3600 tokens decodificados
sin producir nunca una respuesta. Se crearon `compose.nanbeige.yml`/`compose.minicpm5.yml` (nuevos en
esta sesión) para volver a probarlos con el fix ya cableado de forma genérica en los cuatro componentes
`*OpenAi`, mismo razonamiento que ya había confirmado la sesión 23 con `SmolLM3-3B` (mismo modo de falla
original, mismo fix, funcionó).

**`Nanbeige4.1-3B`**: 10/10 sin ningún `error` de conexión -- el fix sí evita el colgado de la sesión 2.
Pero de esas 10, solo 4 fueron respuestas sustantivas: 3 quedaron completamente **vacías** (`error: null`,
`respuesta: ""`, el mismo patrón de "sin respuesta silenciosa" ya visto con otros candidatos, indetectable
para el chequeo de `estadoEsError` de `ejecutar.js` porque no dispara la clase de error en la UI) y 3 más
fueron rechazos legítimos del gate. De las 4 sustantivas, dos mostraron un defecto nuevo no visto en
ningún candidato anterior: **fuga de notación de gramática cruda del JLS en inglés** dentro de la
respuesta (`"PrimitiveType: {Annotation} NumericType {Annotation} boolean..."`, `"double >1 float"`) --
el modelo copió literalmente fragmentos de la especificación formal de Java (que usa esa notación BNF)
en vez de redactar prosa, y en inglés pese al prompt en español.

**`MiniCPM5-1B`**: 9/10 sin `error`, pero la pregunta 8 falló las 3 veces reintentadas con
`"(sin respuesta)"` -- el mismo patrón de vacío silencioso, posiblemente el problema de convergencia
original de la sesión 2 sobreviviendo parcialmente al fix para preguntas puntuales. De las respuestas que
sí llegaron, la calidad es la más baja de todos los candidatos de esta sesión: varias en **inglés**, una
con un error de español que no es una palabra real (`"Pregunjemos las afirmaciones siguientes"`), y otra
que filtró literalmente la plantilla del prompt del sistema dentro de la respuesta
(`"Pregunta: ¿Qué significa...? [1]"`, repitiendo el patrón `PREGUNTA:/CONTEXTO:` del mensaje de usuario
en vez de responderlo).

### Lección operativa: `TaskStop` no mata de forma confiable el árbol de procesos en Windows/Git Bash --
### causó contención real y datos contaminados dos veces en esta sesión

Varias veces durante esta sesión se usó `TaskStop` para cortar una corrida de smoke10 a mitad de camino
(cambio de prioridad del usuario, o un modelo con mal comportamiento evidente). En al menos dos
ocasiones, el proceso bash + `node ejecutar.js` que corría por debajo **sobrevivió** al `TaskStop` --
Windows/Git Bash no garantiza que matar el proceso que el harness rastrea mate también a sus hijos. La
manifestación fue seria: hasta **tres copias** del mismo script de orquestación llegaron a correr en
paralelo al mismo tiempo, todas peleando por el mismo `kb-api`/`kb-llama-server` y escribiendo sobre los
mismos archivos de resultados -- esto generó picos de contención real en `kb-llama-server` (4 slots
concurrentes procesando tareas distintas de la misma GPU, con `cancel task` por timeouts cruzados) y
corrompió el archivo de resultados de `Bonsai-8B` (se descartó y se rehizo desde cero). La forma de
detectar el problema en vivo: `tasklist //FI "IMAGENAME eq node.exe"` mostraba más procesos
`ejecutar.js` de los que deberían existir para una corrida en serie, y `Get-CimInstance Win32_Process`
(PowerShell) permitió trazar el árbol real de padres/hijos hasta encontrar los bash huérfanos y matarlos
con `taskkill //F //T //PID`. **Regla adoptada para el resto de la sesión**: después de cualquier
`TaskStop`, verificar explícitamente con `tasklist`/`Get-CimInstance` que no queden procesos `node.exe`
ni `bash.exe` relacionados antes de lanzar la siguiente corrida -- confiar en el estado "killed" del
notificador del harness no es suficiente.

### Comparación final: velocidad, efectividad y un proxy de energía (sin medición real de vatios)

Con los ocho candidatos que sí generaron datos completos (`nemotron-mini` y `Bonsai` quedaron sin
terminar, excluidos de esta tabla), tres cortes distintos sobre las mismas 10 preguntas:

**Por velocidad** (promedio de latencia de las respuestas que sí llegaron, cualquier calidad):

| # | Modelo | Promedio |
|---|---|---|
| 1 | MiniCPM5-1B | 79 s |
| 2 | qwen2.5:3b | 183 s |
| 3 | SmolLM3-3B | 187 s |
| 4 | phi4-mini:3.8b | 222 s |
| 5 | Nanbeige4.1-3B | 248 s |
| 6 | Ministral 3B | 262 s |
| 7 | granite4.1:3b | 310 s |
| 8 | qwen3.5:4b | 394 s |

**Por efectividad** (respuestas sustantivas: ni vacías, ni rechazadas por el gate, ni error de conexión,
sobre 10 preguntas del corpus de Java que sí tienen respuesta esperada):

| # | Modelo | Sustantivas | Rechazo-gate | Vacías | Error-conexión |
|---|---|---|---|---|---|
| 1 | granite4.1:3b | 10/10 | 0 | 0 | 0 |
| 2 | qwen3.5:4b | 9/10 | 1 | 0 | 0 |
| 3 | qwen2.5:3b | 7/10 | 3 | 0 | 0 |
| 4 | MiniCPM5-1B | 6/10 | 2 | 1 | 1 |
| 4 | SmolLM3-3B | 6/10 | 4 | 0 | 0 |
| 6 | phi4-mini:3.8b | 5/10 | 5 | 0 | 0 |
| 6 | Ministral 3B | 5/10 | 3 | 0 | 2 |
| 8 | Nanbeige4.1-3B | 4/10 | 3 | 3 | 0 |

Un rechazo del gate alto (`phi4-mini:3.8b`, 5/10) no es necesariamente mala síntesis -- es
`VerificadorGrounding` (el mismo LLM en otro rol) rechazando por precaución; puede ser ese modelo siendo
demasiado conservador en esa etapa puntual, no un defecto de `SintetizadorEstructuradoOpenAi`. Aun así,
para el usuario final el resultado es el mismo: no responde.

**Por un proxy de energía** (tamaño del modelo cargado en GB × latencia promedio en segundos -- GB·s,
menor es mejor; **no es una medición real de vatios**, ver más abajo por qué):

| # | Modelo | GB cargados | Proxy (GB·s) |
|---|---|---|---|
| 1 | MiniCPM5-1B | 0.69 | 54 |
| 2 | qwen2.5:3b | 1.9 | 347 |
| 3 | SmolLM3-3B | 1.9 | 354 |
| 4 | phi4-mini:3.8b | 2.5 | 555 |
| 5 | Nanbeige4.1-3B | 2.4 | 594 |
| 6 | granite4.1:3b | 2.1 | 650 |
| 7 | Ministral 3B | 3.0 | 786 |
| 8 | qwen3.5:4b | 3.4 | 1339 |

**La T600 Laptop de esta máquina no expone telemetría de potencia real**: `nvidia-smi
--query-gpu=power.draw --format=csv` devuelve `[N/A]`, confirmado también con `nvidia-smi -q -d POWER`
(todos los campos de potencia, incluidos los de memoria y módulo, en `N/A` -- no es un problema de
permisos, el sensor no está expuesto para este chip en este driver). Se evaluó medir con la pregunta más
rápida en promedio del corpus (pregunta 5, "¿qué representa el tipo null...", 179s de promedio y 8/8
modelos respondiendo bien -- la más rápida y la más consistente a la vez) pero se descartó al confirmar
que no hay vatios reales que medir en este hardware. El proxy de tamaño×tiempo es la mejor aproximación
disponible sin instrumentación externa (un medidor de potencia de pared, fuera del alcance de esta
sesión).

**Velocidad y efectividad al mismo tiempo**: ninguno de los dos cortes de arriba por sí solo dice cuál es
el mejor candidato -- el más rápido (`MiniCPM5-1B`) es de los menos efectivos, y el más efectivo
(`granite4.1:3b`) es de los más lentos. Para verlo junto, se calculó el puesto de cada modelo en cada
ranking por separado (1 = mejor) y se promediaron los dos puestos:

| # | Modelo | Sustantivas /10 | Puesto efectividad | Velocidad prom. | Puesto velocidad | Puesto combinado |
|---|---|---|---|---|---|---|
| 1 | `qwen2.5:3b` | 7 | 3º | 183 s | 2º | **2.5** |
| 2 | `MiniCPM5-1B` | 6 | 5º | 79 s | 1º | 3.0 |
| 3 | `SmolLM3-3B` | 6 | 4º | 187 s | 3º | 3.5 |
| 4 | `granite4.1:3b` | 10 | 1º | 310 s | 7º | 4.0 |
| 5 | `phi4-mini:3.8b` | 5 | 6º | 222 s | 4º | 5.0 |
| 5 | `qwen3.5:4b` | 9 | 2º | 394 s | 8º | 5.0 |
| 7 | `Nanbeige4.1-3B` | 4 | 8º | 248 s | 5º | 6.5 |
| 7 | `Ministral 3B` | 5 | 7º | 262 s | 6º | 6.5 |

`qwen2.5:3b` nunca es el mejor en ningún eje por separado, pero es el único que queda entre los tres
primeros puestos en los dos a la vez -- el mejor balance objetivo, no solo una preferencia cualitativa.

### Conclusión de la sesión 25

**No hay un ganador único** -- depende de qué eje importa más:

- **Más rápido y de menor proxy de energía**: `MiniCPM5-1B`, por lejos (79s, GB·s de 54 -- 6x mejor que
  el segundo lugar en ambos ejes). Pero es también el de peor calidad medida: inglés, español roto,
  filtración de la plantilla del prompt.
- **Más efectivo**: `granite4.1:3b`, el único que respondió las 10 preguntas de verdad sin ningún defecto
  de calidad detectado -- ni el más rápido ni el más liviano, pero el único con 10/10 sustantivas.
- **Mejor balance velocidad+efectividad, con evidencia objetiva (puesto combinado), sin sacrificar
  calidad conocida**: `qwen2.5:3b` -- puesto combinado 2.5 (el mejor de los ocho), sin ningún defecto de
  contenido detectado en sus 7 respuestas sustantivas (los 3 rechazos son del gate, no de la síntesis).
  **Es la recomendación de esta sesión como configuración de síntesis estructurada a seguir probando**,
  por encima de `granite4.1:3b` (más completo pero 7º en velocidad) y de `MiniCPM5-1B` (más rápido pero
  con defectos de calidad que ningún otro candidato del puesto combinado top-3 reproduce).

Comparado con las sesiones 2-23 (candidatos con `SintetizadorOpenAi`, texto libre): la síntesis
estructurada sí resuelve el defecto de "texto pegado" en los tres candidatos que lo tenían
(`granite4.1:3b`, `phi4-mini:3.8b`, `qwen2.5:3b`, hallazgo 95) y el problema de convergencia en salida
JSON forzada en los dos candidatos que lo tenían (`Nanbeige4.1-3B`, `MiniCPM5-1B`, hallazgo 101) --
**pero destapa un problema nuevo, transversal, que ningún candidato de texto libre había mostrado**: al
exigir que TODA la respuesta llegue como un JSON válido de una sola vez, un presupuesto de `maxTokens`
insuficiente ya no produce una respuesta incompleta pero legible (como pasaba con `SintetizadorOpenAi`)
sino una excepción de parseo que, sin los fixes de los hallazgos 92-93, podía dejar el servidor
inutilizable para *cualquier* modelo. El trade-off documentado desde el javadoc de la sesión 24 (perder
el streaming palabra-por-palabra) tiene ahora una segunda cara medida en esta sesión: mayor fragilidad
ante respuestas largas, mitigada pero no eliminada por subir `maxTokens` a ciegas.

Ningún candidato de esta sesión tiene un piloto de 100 preguntas detrás como Ministral (85.3%, sesión
17) -- estas son muestras de 10, útiles para descartar defectos gruesos y comparar en igualdad de
condiciones, no para una cifra de precisión definitiva.

Estado del entorno al cierre: `kb-api` real quedó sirviendo el último perfil probado (`minicpm5`) con
`KB_LLM_SINTESIS_ESTRUCTURADA_HABILITADA=true` -- **no es la configuración de producción**, que sigue
siendo `compose.ministral.yml` sin esa variable. `SintetizadorEstructuradoOpenAi.java` quedó con
`maxTokens=4000` y el fix de `Flux.defer` (hallazgo 92) aplicado -- afecta a cualquier perfil que active
la síntesis estructurada de ahora en más, sin cambiar el comportamiento por defecto
(`kb.llm.sintesis-estructurada.habilitada=false`). Cinco perfiles compose nuevos quedaron en el repo
(`compose.granite41.yml`, `compose.phi4mini.yml`, `compose.qwen25.yml`, `compose.nanbeige.yml`,
`compose.minicpm5.yml`), y `compose.bonsai.yml`/`compose.ministral.yml` quedaron con la variable de
síntesis estructurada agregada. Diez archivos de resultados nuevos en `eval-100-preguntas/` (uno por
candidato con datos, `resultados-brutos.smoke10.<modelo>-estructurada.json`). Nada de esto está
commiteado todavía. Pendiente si se retoma: remedir `Ministral 3B` con `maxTokens=4000` (hallazgo 100),
corregir el hallazgo 94 (mensaje de error legible en vez de conexión cortada), y decidir si algún
candidato de esta sesión justifica un piloto de 100 preguntas propio antes de considerarlo para
producción.

## Sesión 26: el mismo smoke10, pero en texto libre (`SintetizadorOpenAi`) -- comparación directa contra
## la sesión 25, y una sospecha de "degradación" de Ministral resuelta con evidencia en vez de intuición

A pedido del usuario, se repitió el mismo protocolo de la sesión 25 (10 preguntas, mismos ocho modelos,
`kb-api` real, puerta de relevancia en producción) pero con `KB_LLM_SINTESIS_ESTRUCTURADA_HABILITADA=false`
-- el `SintetizadorOpenAi` de texto libre, el que de verdad corre en producción hoy. Objetivo: una
comparación en igualdad de condiciones entre las dos vías de síntesis, no solo comparar la estructurada
contra retazos sueltos de sesiones anteriores (1-2 preguntas por modelo, sesiones 2-23).

Los ocho modelos corrieron en una sola corrida en serie (evitando el problema de procesos duplicados de
la sesión 25) que tardó **más de 20 horas reales**, incluyendo una noche en la que la máquina se durmió.

### Hallazgo 102: artefacto operativo -- el timer de Playwright no sobrevive un sueño de la máquina

Una entrada del resultado de `qwen3.5:4b` quedó con `elapsedMs: 59066730` (~16.4 horas) para una sola
pregunta -- no es latencia real. El timeout de `page.waitForSelector` (900 000 ms) se venció recién
cuando la máquina despertó del sueño nocturno; el reloj del proceso quedó pausado durante el sueño, así
que el `Date.now()` de inicio y fin capturan el sueño completo como si fuera tiempo de espera activo.
Se excluyó esta entrada de cualquier promedio de velocidad. **Lección**: cualquier corrida larga sin
supervisión en esta máquina corre el riesgo de que una suspensión del sistema infle artificialmente los
tiempos medidos -- revisar visualmente cualquier `elapsedMs` que se salga por varios órdenes de magnitud
del resto antes de promediar.

### Hallazgo 103: `Ministral 3B` y `MiniCPM5-1B` no completaron 10/10 -- pero por motivos distintos, uno
### ya conocido desde antes de esta investigación y otro nuevo y real

**`MiniCPM5-1B`**: la pregunta 2 falló con un error real y explícito en los logs de `kb-api`:
`BadRequestException: request (4102 tokens) exceeds the available context size (4096 tokens)` --
desbordó el contexto por apenas 6 tokens. Es el mismo tipo de problema de borde que la sesión 15 ya había
documentado para `VerificadorGrounding` con Ministral (hallazgo cercano al límite físico de 4096 tokens
de este pipeline con el corpus de `jls25.pdf`), aquí afectando a un modelo distinto. Real, reproducible,
sin relación con nada cambiado en esta sesión.

**`Ministral 3B`**: la pregunta 2 falló con `"Se perdió la conexión con el servidor (¿Ollama no
responde?)."`, siempre en 14-21 segundos (no una generación lenta que se corta -- un fallo casi
inmediato). El usuario, con razón, preguntó si esto era una regresión real y pidió revisar qué había
cambiado en el código. La investigación completa está en los hallazgos 104-105.

### Hallazgo 104: la sospecha de "degradación" de Ministral se descartó con evidencia, no con intuición

Tres verificaciones independientes, en este orden:

1. **`git diff` de los cuatro componentes `*OpenAi` que Ministral usa** (`Planificador`,
   `VerificadorGrounding`, `Sintetizador`, `Reformulador`): el único cambio sin commitear es
   `extraBody(reasoning_effort=none)` agregado a los cuatro, documentado desde el propio código como
   inocuo para modelos sin "thinking" nativo como Ministral -- un campo extra que ese backend ignora,
   mismo argumento que ya vale para `repeat_penalty`. Si este campo rompiera algo, se vería como un
   error 400 en los logs (como sí se vio con claridad para el hallazgo 103 de `MiniCPM5-1B`) -- no
   aparece ninguno.
2. **Reproducción en un entorno completamente limpio**: se reinició Docker Desktop entero
   (`wsl --shutdown` + relanzar Docker Desktop, no solo `docker restart kb-api`) para descartar
   acumulación de 45+ horas de uso continuo, y se corrió `Ministral` **aislado**, sin la maratón de ocho
   modelos detrás. La pregunta 2 **volvió a fallar, exactamente igual** (20 387 ms, mismo mensaje) --
   descarta la hipótesis inicial de "fatiga de la sesión larga" que se había manejado en el momento.
3. **Precedente histórico en el propio repo**: `eval-100-preguntas/resultados-completos.ministral-3-3b-100preguntas.json`
   -- el primerísimo piloto de 100 preguntas de Ministral, corrido semanas antes de que existiera
   cualquiera de los componentes o ajustes de esta investigación (`Reformulador`, `reasoning_effort`,
   `tope-por-documento=20`) -- ya tiene la pregunta 2 fallando con el mismo mensaje exacto y una latencia
   igual de rápida (26 271 ms). En el piloto posterior de la sesión 17 (con `tope=20` ya aplicado) esa
   misma pregunta sí respondió bien.

**Conclusión**: es un flake intermitente preexistente, específico de esta pregunta puntual contra
Ministral, documentado en el repo desde antes de que esta investigación empezara -- no una regresión de
ningún cambio de código de las sesiones 20-26. Ninguna otra pregunta de ningún otro modelo de las
sesiones 25-26 mostró este patrón de fallo rápido (14-21s, sin ninguna traza en los logs de `kb-api`) --
es específico de la combinación (Ministral, esta pregunta), no un problema genérico de "la segunda
consulta tras cambiar de perfil".

### Hallazgo 105: tres preguntas de Ministral que antes respondían bien ahora caen en la zona AMBIGUO --
### no es un problema de retrieval, es la inestabilidad ya conocida de `VerificadorGrounding` cerca del
### umbral

Las preguntas 6, 7 y 8 tenían respuestas correctas y completas en el piloto de la sesión 17 (mismo
`tope=20`), pero en esta corrida las rechazó la puerta de relevancia. Consultando `query_log` en vivo
para la pregunta 6 ("¿qué significa que un método tenga tipo de retorno void?"): el fragmento correcto
de `jls25.pdf` **sí fue recuperado** (candidato top, `rerank=7.18`) -- no es una falla de búsqueda ni de
embeddings. El problema es que 7.18 queda por debajo del `KB_UMBRAL_RELEVANCIA_TECHO_CONFIANZA=8.0`
configurado para Ministral, cae en la zona AMBIGUO, y ahí decide `VerificadorGrounding` -- que esta vez
juzgó que no alcanzaba, pese a que el contenido es claramente relevante.

Esto no es nuevo ni sorprendente a la luz del resto de la investigación: el ADR-0008 y el hallazgo 61
(sesión 16) ya habían diagnosticado que el juicio binario de `VerificadorGrounding` en la zona ambigua
(puntajes entre el piso de "insuficiente" y el techo de "suficiente") no es perfectamente estable --
incluso con `temperature(0.0)`, el muestreo en GPU no garantiza determinismo bit a bit entre ejecuciones
distintas, y un puntaje cerca del límite (7.18 de 8.0) puede cruzar para un lado u otro entre corridas.
En la sesión 17 cruzó a "sí"; en esta corrida cruzó a "no". La pregunta 4 (`autoboxing`), en cambio, ya
fallaba igual en la sesión 17 -- ese rechazo sí es consistente entre corridas, no forma parte de este
hallazgo.

### Comparación final: velocidad, efectividad y puesto combinado (texto libre)

Mismo cálculo que el cierre de la sesión 25 (puesto 1 = mejor en cada eje, promedio de los dos puestos).
Para `Ministral 3B` se usa la corrida aislada y limpia del hallazgo 104 (9/10, más comparable
metodológicamente que la de la maratón, aunque ambas dan el mismo resultado):

| # | Modelo | Sustantivas /10 | Puesto efectividad | Velocidad prom. | Puesto velocidad | Puesto combinado |
|---|---|---|---|---|---|---|
| 1 | `MiniCPM5-1B` | 7 | 5º | 94 s | 1º | **3.0** |
| 1 | `qwen2.5:3b` | 8 | 3º | 187 s | 3º | **3.0** |
| 3 | `SmolLM3-3B` | 5 | 7º | 179 s | 2º | 4.5 |
| 3 | `qwen3.5:4b` | 9 | 2º | 286 s | 7º | 4.5 |
| 3 | `granite4.1:3b` | 10 | 1º | 292 s | 8º | 4.5 |
| 6 | `Nanbeige4.1-3B` | 7 | 4º | 279 s | 6º | 5.0 |
| 7 | `phi4-mini:3.8b` | 6 | 6º | 235 s | 5º | 5.5 |
| 8 | `Ministral 3B` | 5 | 8º | 207 s | 4º | 6.0 |

"Sustantivas" cuenta lo mismo que en la sesión 25: ni vacías, ni rechazadas por el gate, ni error de
conexión -- no mide si la prosa en sí pega el fragmento crudo (el defecto que motivó la síntesis
estructurada en la sesión 24) o no; para eso hace falta leer las respuestas, no solo contarlas. `qwen2.5:3b`
empata con `MiniCPM5-1B` en el mejor puesto combinado, pero con casi el doble de sustantivas (8 vs 7) y
sin los defectos de calidad que la síntesis estructurada ya le había medido a `MiniCPM5-1B` (inglés,
español roto) -- en texto libre no se verificó si esos mismos defectos persisten, queda pendiente si se
retoma.

### Conclusión de la sesión 26

**Comparado con la sesión 25 (síntesis estructurada), el orden de mejores candidatos cambia poco en la
cima pero el "ganador" por puesto combinado no es el mismo modelo**: en estructurada ganaba
`qwen2.5:3b` solo (puesto 2.5); en texto libre empata con `MiniCPM5-1B` (puesto 3.0 cada uno). `granite4.1:3b`
se mantiene como el único con 10/10 sustantivas en ambos modos -- el candidato más consistente de las
dos sesiones, aunque nunca el más rápido. `Ministral 3B` (el modelo de producción) queda en el último
puesto combinado de esta tabla, pero por las dos razones diagnosticadas en los hallazgos 104-105 -- un
flake preexistente sin relación con esta investigación, y la fragilidad ya conocida de la zona
AMBIGUO -- no por ningún cambio de código real. **No confundir esta muestra de 10 preguntas con el
85.3% del piloto de 100 preguntas de la sesión 17**: son números que miden cosas distintas (10 preguntas
elegidas de antemano vs. 100 preguntas con corrección manual), y esta sesión no reemplaza esa cifra.

Estado del entorno al cierre: Docker Desktop se reinició por completo durante esta sesión (`wsl
--shutdown`, hallazgo 104) -- el stack volvió a levantar solo con `docker compose`, sin pérdida de
datos. `kb-api` real quedó sirviendo el último perfil probado (`minicpm5`, texto libre) --
**no es la configuración de producción**, que sigue siendo `compose.ministral.yml` sin la síntesis
estructurada activa. Nueve archivos de resultados nuevos en `eval-100-preguntas/`
(`resultados-brutos.smoke10.<modelo>-textolibre.json`, más
`resultados-brutos.smoke10.ministral-textolibre-limpio.json` de la corrida aislada). Nada de esto está
commiteado todavía.
