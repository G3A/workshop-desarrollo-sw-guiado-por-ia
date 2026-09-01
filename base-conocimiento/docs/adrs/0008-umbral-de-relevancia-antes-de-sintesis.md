# ADR-0008: Umbral dinámico de relevancia + verificación de grounding antes de sintetizar

## Estado

Aceptado (post-F4).

## Contexto

El artículo de Cerebras nunca filtra el top-N por puntaje mínimo: *"le da a cada documento un
puntaje de 0 a 10, y nos quedamos con el top 10"*, sin importar qué tan débil sea. La única defensa
contra el contexto irrelevante es el propio prompt de síntesis (`SintetizadorOllama`): *"Si el
contexto no alcanza para responder la pregunta, dilo explícitamente en vez de inventar."*

Esa defensa se probó en vivo y falló. Con el corpus semilla del taller (README.md +
despliegue.md, 4 chunks) y la pregunta fuera de dominio *"explícame cómo usar Java 25"`:

- `gemma3:4b` (modelo por defecto) paseó los fragmentos del README sobre cómo desplegar el propio
  proyecto y los redactó en prosa fluida como si fueran la respuesta a "cómo usar Java 25", citando
  `[1]`/`[2]`/`[4]` reales pero irrelevantes a la pregunta.
- `granite4.1:3b`, probado como alternativa, no mejoró el problema: su etapa de planificación
  directamente inventó una "corrección" silenciosa (*"ya que 'Java 25' no es una versión
  reconocida, [busquemos] Java 11"*) y la síntesis final ni siquiera generó prosa — pegó el texto
  crudo de los fragmentos con el marcador `[n]` sin rellenar.

Ningún modelo local de 3-4B, sin importar el prompt, discriminó de forma confiable "este contexto
es tangencial, no responde la pregunta" cuando el contexto es débilmente relacionado en vez de
vacío. Depender de que el LLM se autocensure es frágil por diseño a esta escala de modelo.

### Calibración empírica del cross-encoder

Contra `/api/search` con el mismo corpus semilla, el campo `rerank` (`sigmoid(logit) * 10`, ver
`RerankerOnnx`) del mejor candidato:

| Pregunta | Naturaleza | mejor `rerank` |
|---|---|---|
| "Se necesita Docker Desktop con el motor iniciado" | copia casi literal de un fragmento real | 9.84 |
| "como se despliega el servicio" | relevante, parafraseada | 0.0055 |
| "explicame como usar Java 25" | irrelevante, comparte vocabulario (Java, usar) | 0.0019 |
| "cual es la capital de Australia" | sin relación alguna con el corpus | 0.00017 |

El cross-encoder sí separa por orden de magnitud (cada nivel de irrelevancia es 3-10 veces más
chico que el anterior), pero el rango útil está comprimido muy cerca de cero salvo coincidencia casi
textual — un corte "intuitivo" como 5.0 (mitad de la escala 0-10 documentada) nunca se cumpliría, ni
para preguntas genuinamente relevantes parafraseadas.

### El umbral por score solo no alcanza: contraejemplo real

La primera versión de esta puerta fue un único umbral dinámico por score (ver más abajo). Cerrado
el caso de "explícame cómo usar Java 25" (`rerank` 0.0019, bloqueado), la misma pregunta reformulada
más corta, **"como usar java 25"**, se coló y volvió a producir la misma alucinación — el
sintetizador redactó otra vez las instrucciones de despliegue de este proyecto como si fueran la
respuesta.

El motivo quedó expuesto al comparar los tres puntajes reales:

| Pregunta | Naturaleza | mejor `rerank` |
|---|---|---|
| "como se despliega el servicio" | **relevante de verdad** | 0.0055 |
| "explicame como usar Java 25" | irrelevante (bloqueada por el umbral) | 0.0019 |
| **"como usar java 25"** | irrelevante (coló por el umbral) | **0.0134** |

La pregunta irrelevante puntuó **más alto** que la relevante. Ningún umbral de un solo número puede
separar los tres casos: subir el corte lo suficiente para bloquear 0.0134 bloquea también 0.0055,
la respuesta buena. La hipótesis de que el corpus semilla es mono-temático (una sola nota sobre cómo
correr *este* proyecto, redactada en imperativo — "Copia...", "Corre...", "Ejecuta...") explica por
qué: sin nada más en el corpus con qué contrastar, el cross-encoder parece engancharse con la forma
superficial de la pregunta (corta, tono instructivo, comparte "usar"/"Java") tanto como con el
significado real.

## Decisión

Se agrega una puerta de dos capas en la etapa 4 del pipeline (`Orquestador`, después de
`FusionDeHerramientas`, antes de expandir contexto y sintetizar):

**Capa 1 — umbral por score (`UmbralRelevancia`, sin llamar a Ollama).** Dinámico según el tamaño
del corpus del proyecto (`chunks` ingeridos), no un número fijo:

```
umbral(n) = piso + (techo - piso) * min(1, n / chunksReferencia)
```

- `piso` (default `0.003`): el mínimo exigido incluso con un corpus recién sembrado — calibrado
  para bloquear "explícame cómo usar Java 25" (0.0019) y "capital de Australia" (0.00017) sin
  bloquear "cómo se despliega el servicio" (0.0055) en el corpus semilla de hoy.
- `techo` (default `0.05`): el mínimo exigido una vez el proyecto alcanza `chunksReferencia`
  (default 500) chunks — sin dato propio que lo respalde todavía, es una extrapolación razonada de
  la misma calibración, no una medición.
- Por debajo de `umbral(n)`: `INSUFICIENTE`, se rechaza de inmediato, sin gastar ninguna llamada a
  Ollama.

**Capa 2 — verificación de grounding (`VerificadorGrounding`, una llamada corta a Ollama).** El
contraejemplo de arriba demostró que el score, aunque supere el umbral, no certifica relevancia
real hasta un segundo límite, `techoConfianza` (default `8.0`) — muy por debajo del cual cae
prácticamente todo salvo una copia casi literal (`rerank` ~9.8):

- Entre `umbral(n)` y `techoConfianza`: `AMBIGUO`. Se arma el contexto (etapa 5, con vecinos) y se
  manda a `VerificadorGrounding.verificar(pregunta, contexto)` — una clasificación binaria con
  salida estructurada forzada (mismo patrón que `PlanificadorOllama`), no una redacción libre: más
  confiable a esta escala de modelo que pedirle al sintetizador que se autocensure en la misma
  llamada donde redacta. Si el veredicto es `false`, se rechaza con `MENSAJE_SIN_INFORMACION`; si es
  `true`, se sintetiza normal con el contexto ya armado.
- Por encima de `techoConfianza`: `SUFICIENTE` directo, sin gastar la llamada de verificación —
  prácticamente una copia literal, no hay ambigüedad real que resolver.
- Si `VerificadorGrounding` falla (Ollama caído, respuesta que no valida contra el esquema), el
  respaldo es **rechazar**, no aceptar — al revés que `PlanificadorOllama` (que se cae a
  `search_unified`): esta es la última defensa contra una respuesta sin respaldo verificado, y
  arriesgar una alucinación es peor que negarse cuando no se pudo verificar.

Ninguna de las dos capas se aplica a fragmentos que no traen `rerank` (`recent_commits`,
`subsystem_index`, `who_knows` — herramientas de listado/agregado que no rankean por relevancia a la
consulta, ver `HerramientasRepositorio`): si el resultado fusionado incluye alguno de esos, se deja
pasar sin más, porque la elección de esa herramienta por el planner ya es la señal, y ni el score ni
el verificador tienen forma de juzgarla.

### Segundo contraejemplo: `techoConfianza` también estaba mal calibrado, y el patrón se repite con otra herramienta

Con `techoConfianza=1.0` puesto en producción, la pregunta **"como se configura docker?"** obtuvo
`rerank` **3.499** — por encima de `techoConfianza`, así que saltó directo a `SUFICIENTE` sin pasar
por `VerificadorGrounding`, y produjo el mismo tipo de respuesta de alcance equivocado: los pasos de
`docker compose up` de *este* proyecto, presentados como si respondieran una pregunta genérica sobre
Docker como herramienta. Es el mismo patrón que "Java 25" (una tecnología nombrada de forma genérica,
contestada con el uso puntual que hace *este* proyecto de ella), solo que con un score mucho más
alto — 3.499 no es ni remotamente una copia literal (~9.8) pero tampoco cayó en el rango de una
pregunta relevante parafraseada normal (~0.005). `techoConfianza` subió a `8.0` para que este caso
también pase por verificación.

Eso solo no bastó: `VerificadorGrounding` con su prompt original, ya en la zona `AMBIGUO`, igual
respondió `true` — un veredicto defendible en abstracto (la única información sobre Docker en el
corpus es la de este proyecto), pero no lo que la persona pidió. El prompt del verificador se amplió
con un segundo ejemplo explícito para esta forma del problema: pregunta genérica sobre una
herramienta/tecnología, sin mencionar el proyecto/servicio/app, contestada con un contexto que solo
cubre el uso de esa herramienta *para este proyecto puntual* → `false`, aunque el contexto sea
real y esté bien recuperado.

De paso, esta misma iteración expuso una segunda falla independiente: el marcador de cita `[n]`
aparecía a veces *antes* de la afirmación que debía respaldar, o agrupado como `[1], [3]` suelto al
final del texto sin decir qué frase respaldaba. `SintetizadorOllama` no daba ninguna instrucción
sobre *dónde* colocar la cita, solo que debía existir. Se agregó al prompt un ejemplo correcto y uno
incorrecto de ubicación — esto es un defecto de formato de citas, no de relevancia, pero se
descubrió y corrigió en la misma sesión de pruebas.

### Verificación en vivo, segunda ronda

Contra la API real (`gemma3:4b`), después de las tres correcciones (`piso`/`techo` iniciales,
`techoConfianza` subido a `8.0`, prompt de `VerificadorGrounding` ampliado, prompt de citas
corregido):

| Pregunta | mejor `rerank` | Decisión | Resultado |
|---|---|---|---|
| "explicame como usar Java 25" | 0.0019 | `INSUFICIENTE` (sin llamar a Ollama) | Rechaza ✅ |
| "como usar java 25" | 0.0134 | `AMBIGUO` → verificador dice **no** | Rechaza ✅ |
| "como se configura docker?" | 3.499 | `AMBIGUO` → verificador dice **no** | Rechaza ✅ |
| "como se despliega el servicio" | 0.0055 | `AMBIGUO` → verificador dice **sí** | Sintetiza con citas bien ubicadas ✅ |

### Tercera ronda: pruebas de control más amplias, dos fallos nuevos, y una causa raíz distinta

Antes de dar el ADR por cerrado se corrió un lote de 9-10 preguntas de control cubriendo patrones
de riesgo distintos (otras tecnologías mencionadas en el corpus, preguntas totalmente ajenas,
saludos, preguntas explícitamente acotadas al proyecto). Aparecieron dos falsos negativos:

**1. "¿que gpu necesita este proyecto?" rechazada sin motivo real de relevancia.** La traza mostró
que el `Planificador` eligió `search_code` y `subsystem_index` — razonando *"necesito ver qué
bibliotecas de ML se usan en el código"* — y ninguna de las dos trajo nada (`search_code` no tiene
repos indexados en la demo; `subsystem_index` está vacío). Nunca se llamó a `search_docs`, que sí
tiene el fragmento correcto (la nota de GPU NVIDIA / VRAM). **No era un fallo de `UmbralRelevancia`
ni de `VerificadorGrounding` — era el `Planificador` escogiendo mal la herramienta** para una
pregunta de requisitos de hardware. Se corrigió en dos puntos:
- `HerramientaSearchCode.descripcion()` ahora dice explícitamente "NO para preguntas de requisitos
  de hardware, instalación, despliegue o configuración".
- El prompt de `PlanificadorOllama` ganó una regla explícita: preguntas de hardware/instalación/
  despliegue/configuración van a `search_docs`/`search_unified`, no a `search_code`.

**2. "¿para qué sirve `.env.example`?" rechazada pese a un `rerank` altísimo (0.957, claramente
relevante).** Aquí sí falló `VerificadorGrounding`: aplicó la regla de "tecnología externa" a un
archivo que solo existe porque este proyecto lo define — no hay ningún ".env.example genérico" con
el que confundirse, así que la regla no debía aplicar. Se agregó una excepción puntual y acotada al
prompt: para archivos/comandos propios del proyecto (no tecnologías externas como Java o Docker),
cualquier explicación real de su uso responde la pregunta, aunque sea breve.

**Hallazgo aparte, más importante que cualquier ajuste de prompt: no-determinismo.** Al agregar la
excepción de arriba, las preguntas que ya funcionaban bien (Docker, GPU, Ollama, WSL2 genéricos)
empezaron a fallar de forma inconsistente entre corridas — a veces se rechazaban, a veces no, con el
**mismo prompt y el mismo contexto**. Se verificó corriendo la pregunta *"¿que es Ollama?"* tres
veces seguidas sin cambiar nada: rechazó en 2 de 3 intentos y aceptó en el tercero. La causa:
`VerificadorGrounding` heredaba `temperature=0.2` (el default global de `kb.llm` en
`application.yml`), pensado para que el `Sintetizador` no suene robótico — pero un veredicto
binario sobre si arriesgar una alucinación no debería depender de una tirada de dados. Se fijó
`temperature=0.0` solo en `VerificadorGroundingOllama`, sin tocar el resto de los modelos.

Con `temperature=0.0` las pruebas se volvieron reproducibles: los dos fallos originales (GPU
escondida, `.env.example`) quedaron arreglados y estables en corridas repetidas. Pero expuso un
límite real, no un espejismo de la aleatoriedad: **"¿como se configura docker?" y "¿que es WSL2?"
siguen respondiendo con el uso puntual de este proyecto de forma consistente**, mientras que "¿que
es una GPU?" y "¿que es Ollama?" rechazan consistentemente. La diferencia aparente: Docker y WSL2
tienen varios pasos de configuración detallados alrededor suyo en el corpus (más "superficie" de
texto que el modelo asocia con "esto sí explica algo"), mientras que GPU y Ollama aparecen como
menciones de paso, más fáciles de reconocer como "no una definición". Este residuo queda documentado
como limitación conocida en vez de seguir parchando el prompt caso por caso — el propio riesgo que
ya se había anotado más abajo en este ADR.

### Verificación final en vivo (tercera ronda, con `temperature=0.0`)

| Pregunta | Resultado | Reproducible en 2 corridas idénticas |
|---|---|---|
| "para que sirve el archivo .env.example?" | Responde con contenido correcto | Sí ✅ |
| "que gpu necesita este proyecto?" | Responde con contenido correcto | Sí ✅ |
| "como se despliega el servicio" | Responde con contenido correcto | Sí ✅ |
| "cuanta memoria necesita WSL2 para este proyecto?" | Responde con contenido correcto | Sí ✅ |
| "explicame como usar Java 25" | Rechaza | Sí ✅ |
| "cual es la capital de Francia?" | Rechaza | Sí ✅ |
| "que es una GPU?" | Rechaza | Sí ✅ |
| "que es Ollama?" | Rechaza | Sí ✅ |
| "como se configura docker?" | Responde (alcance equivocado) | Sí — **limitación conocida** |
| "que es WSL2?" | Responde (alcance equivocado) | Sí — **limitación conocida** |

## Consecuencias

- **A favor**: cierra el hueco demostrado, incluido el contraejemplo donde un solo score no
  alcanzaba. El verificador de grounding es un juicio semántico real (clasificación binaria acotada)
  en vez de un número — más robusto que ambas alternativas probadas por separado (el prompt de
  síntesis solo, y el umbral por score solo).
- **En contra — desviación explícita del artículo de Cerebras**: el artículo nunca hace ningún
  corte de relevancia; siempre entrega el top-10 al LLM. Esa decisión tiene sentido en su contexto
  (corpus de toda una empresa, donde casi cualquier pregunta real tiene *algo* genuinamente
  relevante, y presumiblemente un modelo de síntesis más capaz que uno de 3-4B local). Ninguna de
  las dos condiciones se sostiene en este taller — de ahí que aquí sí se justifique apartarse, ahora
  con dos capas en vez de una.
- **Costo extra en la zona ambigua**: cada pregunta que caiga entre `umbral(n)` y `techoConfianza`
  paga una llamada adicional a Ollama antes de decidir si sintetiza. Dado lo comprimidos que están
  los puntajes en este corpus (ver la tabla de arriba), la mayoría de las preguntas reales van a
  caer ahí — es decir, hoy casi toda consulta paga esta llamada extra. Aceptable a la escala de este
  taller; a revisar si la latencia importa a mayor escala.
- **Calibración débil, y ya se probó frágil en la práctica**: `piso`, `techo` y `techoConfianza`
  salen de un solo corpus de 4 chunks y un puñado de preguntas de prueba, no de un dataset.
  `techoConfianza` ya tuvo que subir una vez (`1.0` → `8.0`) tras un contraejemplo real encontrado en
  uso normal, no en un test escrito de antemano — es evidencia de que estos números seguirán
  moviéndose a medida que aparezcan más preguntas reales, no de que ya estén bien puestos.
- **El patrón "tecnología genérica vs. uso puntual de este proyecto" es recurrente, no un caso
  aislado, y sigue sin resolverse del todo**: apareció con Java, con Docker, y con WSL2 — cada uno
  con una forma distinta de fallar (rerank bajo, rerank alto, y ahora un caso que ni bajando
  `techoConfianza` ni afinando el prompt del verificador logró cerrarse). "que es una GPU?" y "que es
  Ollama?" sí se resuelven bien con el prompt actual; "como se configura docker?" y "que es WSL2?"
  no, de forma consistente. Reintentar reescribir el prompt para estos dos casos puntuales ya mostró
  rendimientos decrecientes (la sección de arriba documenta cómo cada intento arregló un caso y rompió
  otro) — la vía más prometedora si esto sigue importando no es un tercer o cuarto ajuste de prompt,
  sino un corpus más grande y variado que le dé al modelo con qué contrastar "definición general" de
  "uso puntual".
- **Lección aparte, más valiosa que cualquier ajuste de prompt: la temperatura del modelo importa
  tanto como el contenido del prompt para un verificador binario.** `VerificadorGrounding` heredaba
  `temperature=0.2` del resto de `kb.llm`, y eso por sí solo hacía que el mismo veredicto cambiara
  entre corridas idénticas (verificado en vivo: 2 de 3 intentos rechazaban, 1 aceptaba, con el mismo
  prompt y el mismo contexto). Ya está en `0.0` solo para este componente. Cualquier otra puerta de
  decisión binaria que se agregue a este pipeline debería nacer con `temperature=0.0` por defecto,
  no heredar la del resto del sistema.
- Si el mensaje fijo aparece con demasiada frecuencia sobre preguntas que sí deberían tener
  respuesta, ahora hay dos palancas a mirar en orden: primero si `VerificadorGrounding` está
  rechazando de más (su propio prompt, o revisar si Ollama está fallando y cayendo al respaldo
  conservador), y solo después `piso`/`techoConfianza`. Deshabilitar toda la puerta con
  `KB_UMBRAL_RELEVANCIA_HABILITADO=false` sigue disponible como escape completo.
