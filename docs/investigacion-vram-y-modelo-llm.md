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
