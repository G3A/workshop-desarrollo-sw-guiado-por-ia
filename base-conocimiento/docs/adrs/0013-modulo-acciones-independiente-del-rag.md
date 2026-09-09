# ADR-0013: el módulo `acciones` es independiente del RAG

## Estado

Aceptado (2026-09-07, issue #38).

## Contexto

La página de chat ya dejaba tildar documentos por conversación para acotar el RAG. El pedido fue
hacer con esa selección cosas que no son una pregunta: resumir, sintetizar, proponer preguntas e
ideas, y traducir (documentos completos y también texto del chat). Ninguna tiene un término que
buscar: el planner no tendría herramientas que elegir y el umbral de relevancia (ADR-0008)
rechazaría la consulta por no tener un candidato fuerte.

Se evaluaron tres opciones:

1. **Una etapa más del pipeline de siete etapas.** Resumir como un modo de `Orquestador` que se
   salta planner, retrieval y umbral con condicionales. Una primera implementación fue por acá:
   compartía `Consultar`, el cupo de consultas, `query_log` y `streams_en_curso`, y el
   `Sintetizador` con un prompt aparte. Funcionaba, pero cada acción nueva sumaba un condicional
   al núcleo del RAG y el prompt de síntesis («responde SOLO la pregunta», «di que no alcanza»)
   contradecía lo que se pedía.
2. **Un módulo propio que reutiliza la auditoría y el feedback del RAG.** Registrar cada acción en
   `query_log` y mostrar 👍/👎. Gana trazabilidad, pero acopla el módulo a `orquestacion` por dos
   cosas que no son su trabajo, y el esquema de `query_log` (plan, herramientas, candidatos) no
   describe una acción sin retrieval.
3. **Un módulo propio que comparte solo el vault indexado y el cliente del LLM.** Ni `Consultar`,
   ni planner, ni retrieval, ni umbral, ni `query_log`, ni reconexión tras un F5.

Restricciones: el LLM local tiene un contexto chico (4096 tokens con Bonsai, ADR-0009) y los
documentos del vault pueden tener cientos de secciones; la UI es HTML/JS sin build y su único
canal de streaming es `EventSource`; los adaptadores tienen prohibido tocar el núcleo
(`ArquitecturaTest`).

## Decisión

La opción 3. Nace el módulo Spring Modulith `acciones`, con su fachada pública `Acciones`, que
comparte con `orquestacion` exactamente dos cosas y ninguna es código: los documentos ya
indexados (SQL propio sobre `documents`/`chunks`, filtrado por `project_id`) y el cliente del LLM
(`llm.Redactor`, con prompts propios de resumir y traducir). Los adaptadores pueden cruzar ahora
dos puertas, `Consultar` y `Acciones`, y una regla nueva de ArchUnit prohíbe que `acciones`
dependa de `orquestacion`, `recuperacion`, `ingesta`, `modelos` o los adaptadores.

Dos decisiones derivadas que también cierran aquí:

- **Todo documento elegido entra entero; lo que se declara es cómo.** `PresupuestoDeContexto`
  reparte un tope de caracteres (7000 por defecto, calibrado para 4096 tokens descontando prompt y
  salida) en partes iguales con redistribución. El que cabe entra tal cual; el que no se lee por
  pasadas (tramos condensados en notas con el LLM, y las notas vueltas a condensar hasta que
  quepan), y la cobertura dice cuántas pasadas son, con progreso mientras corre. La primera
  versión recortaba el documento a lo que cabía y avisaba «parcial»; con un solo documento largo
  el aviso pedía algo imposible y la acción no lo cubría (sub-issue #60). Es la misma regla de
  ADR-0008 («nunca ocultar la falta de evidencia») llevada a leerlo todo en vez de declarar lo que
  no se leyó.
- **Preguntas e ideas van con salida estructurada**, sin token a token, para que la UI pinte desde
  datos (botón «Preguntar» por pregunta, tarjetas por idea) en vez de depender de que un modelo de
  4B respete un formato de listas.
- **La lluvia de preguntas sigue un método, no un prompt suelto** (sub-issue #61): un nivel de la
  taxonomía de Bloom por llamada, en orden, y dentro de cada nivel la plantilla 5W1H (una pregunta
  por interrogativo como máximo, solo si un documento la responde). El tope sale del método, no de
  una cuota arbitraria: hasta seis por nivel, y un nivel puede quedar vacío. Una llamada por nivel
  mantiene cada salida corta (cabe en el timeout en CPU) y deja emitir el resultado acumulado, así
  la UI muestra lo que ya hay y por cuál nivel va. Lo que un modelo de 4B devuelve de más se quita
  con reglas verificables, no con más prompt: solo cuentan los textos que terminan en signo de
  interrogación (el modelo copiaba afirmaciones del documento) y cada nivel recibe las preguntas
  ya formuladas y descarta las repetidas, con o sin acentos.

## Consecuencias

- **A favor**: reemplazar o quitar el RAG no toca las acciones, y viceversa; cada acción nueva es
  un método del `Redactor` y un servicio del módulo, no un condicional en `Orquestador`; el módulo
  se prueba con dobles del repositorio y del LLM sin levantar el pipeline; los prompts de resumir y
  traducir no cargan con las reglas de responder una pregunta.
- **En contra**: las acciones no tienen feedback 👍/👎 ni fila en `query_log` — si hace falta
  auditarlas, será una tabla propia, no `query_log`; un F5 a mitad de una acción pierde el
  resultado en curso (no hay `streams_en_curso` para ellas); hay dos semáforos de concurrencia
  sobre los mismos slots de Ollama, así que sumados pueden superar `OLLAMA_NUM_PARALLEL` y en ese
  caso Ollama encola y la acción tarda más; el traductor del chat necesitó un canal de streaming
  por `fetch` (POST) además de `EventSource`, con su propio parser SSE en la página.

Reconsiderar si aparece una necesidad real de auditar las acciones junto a las consultas, o si el
RAG y las acciones empiezan a compartir más que el vault y el LLM (por ejemplo, un cupo global):
en ese momento la frontera vale menos que la duplicación.
